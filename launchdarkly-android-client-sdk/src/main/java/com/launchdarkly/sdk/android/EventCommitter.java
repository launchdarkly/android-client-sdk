package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventSummarizer;
import com.launchdarkly.sdk.internal.events.Sampler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where {@link DirectEventProcessor} accepts events, holds them and commits them to the store.
 * <p>
 * Recording an event summarizes it immediately and, only if it has to be delivered in full, holds it. A full
 * event is kept as an {@link Event} until a commit turns the run of them into bytes and writes them. So
 * evaluations of untracked flags cost a counter increment, and evaluations of tracked ones cost that plus a
 * list append. Nothing is encoded on the thread that evaluated.
 * <p>
 * Where the line falls is the subject of O12 in the event-encoding research, and it falls between the events
 * an application asked for by name and the ones it did not. {@code track} and {@code identify} are commit
 * points, because an application reporting an error is saying this matters more than the microseconds it
 * costs, and the crash it describes may be moments away. A commit encodes the whole held run, so the
 * exposures leading up to the error go down with it. Evaluations get no such promise and are committed once
 * {@link #PENDING_COMMIT_THRESHOLD} of them have accumulated where persistence is on, on {@code flush}, or
 * when the application flushes from its own crash handler.
 * <p>
 * Whether a commit point runs on the caller's thread is the application's choice, through
 * {@link com.launchdarkly.sdk.android.integrations.EventProcessorBuilder#eventPersistence}. Only at
 * {@code IMMEDIATE} can {@code track} promise the event is on disk by the time it returns.
 * <p>
 * Threads. Recording runs on whichever thread evaluated or tracked. Commits run on {@link #commitExecutor},
 * or on the caller's thread at a commit point, one at a time under {@link #commitLock}. A delivery commits
 * too, on the event processor's delivery thread, before it closes a batch.
 */
final class EventCommitter {
    /**
     * How many full events may be held unencoded before one of them pays for a commit.
     * <p>
     * It counts events rather than bytes because there are no bytes until the commit runs. Each commit writes
     * everything it encoded at once, so this, with the commit points, is what decides how large each write to
     * disk is. It sets two things at once.
     * <p>
     * The first is how many evaluations a termination can take. That is narrower than everything a crash
     * could take. An uncaught exception runs the application's handler while the process is still alive, and
     * a handler that flushes commits the whole held run. So this is the window for the terminations that run
     * nothing on the way out -- {@code SIGKILL}, a native crash, an ANR kill, and the system reclaiming a
     * backgrounded process. A {@code track} closes it too, because it commits.
     * <p>
     * The second is what a {@code track} usually costs, since it encodes whatever is held before returning.
     * The commit executor keeps the run drained, so the common case is far below this; raising it trades that
     * cost against the number of writes.
     * <p>
     * Neither is a ceiling. Reaching this only schedules a commit, and recording does not wait for it, so
     * where events arrive faster than the commit executor encodes them, or the executor is starved, the run
     * grows up to the event capacity, and the next commit point encodes all of it.
     */
    private static final int PENDING_COMMIT_THRESHOLD = 32;

    // Set in the constructor and never changed.

    private final EventStore store;
    /**
     * Where a commit no caller waits for runs: a thread of its own, not the event processor's delivery thread.
     * <p>
     * That thread can be held by one delivery for as long as two network timeouts. A commit queued behind
     * that would leave a {@code DEFERRED} {@code track} in memory for all of it, and let the pending run fill
     * toward capacity meanwhile.
     */
    private final ExecutorService commitExecutor;
    /**
     * How many events the SDK will hold in total, across {@link #pending} and the store.
     */
    private final int capacity;
    /**
     * Whether a commit point encodes and writes before returning, rather than queuing that work.
     */
    private final boolean commitOnCallerThread;
    /** The event processor's: set once it closes, after which nothing more is recorded. */
    private final AtomicBoolean closed;
    /** The event processor's count of events accepted and then lost for good, which flushes answer for. */
    private final AtomicLong lostEvents;
    /** The event processor's count of events dropped for capacity, which diagnostics carry. */
    private final AtomicLong droppedEvents;
    private final LDLogger logger;

    // Guarded by commitLock.

    /**
     * Held for the whole of a commit, so that only one runs at a time.
     * <p>
     * This is what {@code track}'s guarantee rests on. Without it a commit already in flight could take the
     * caller's event out of {@link #pending} before the caller got there, leaving the caller nothing to
     * write and returning while those bytes were still being produced somewhere else. Waiting here instead
     * means that when the call returns the event is on disk, whichever commit put it there.
     * <p>
     * Distinct from {@link #recordLock}, which is only held long enough to hand the run and the counters
     * over: encoding under this lock must not block a thread that is merely recording.
     */
    private final Object commitLock = new Object();

    /**
     * The last recording a finished commit covered. Written under {@link #commitLock}, and read outside it
     * so that a caller whose recording is already durable does not queue for the lock at all.
     * <p>
     * This is what makes concurrent {@code IMMEDIATE} commit points a group commit. The first caller's
     * commit takes every recording made before it took the run, including those of callers now waiting
     * behind it. They find their recordings covered and return, and the next caller still uncovered
     * commits everything that arrived in the meantime. Without it each waiter committed again in turn,
     * and each of those commits wrote a summary of its own.
     */
    private volatile long committedSequence;

    // Guarded by recordLock.

    /**
     * Guards everything one recording writes: {@link #pending} and the summary counters behind
     * {@link #eventBuffer}.
     * <p>
     * One evaluation can produce a counter, a full event and a debug event, and the three have to
     * land together. Taken separately, a flush landing between them splits one evaluation across two
     * payloads. A {@code close()} landing between them delivers the counter and then refuses the
     * full event, leaving a summary that says an evaluation happened and no event to go with it.
     * <p>
     * Held for the appends and the handover of the run, never across the encoding. Recording runs on
     * whichever thread evaluated a flag, which on Android is usually the main one. The worst it may
     * wait for is another thread's memory operation; if the encoder ran under this lock, an
     * evaluation would instead wait on the dominant cost of the whole path.
     */
    private final Object recordLock = new Object();

    /**
     * Full events recorded but not yet encoded, guarded by {@link #recordLock}.
     * <p>
     * Held rather than serialized because serializing early would not make them durable: the store stages
     * bytes into memory too, and only {@link EventStore#commit()} reaches the file. Both forms are equally
     * lost to a crash, so the encoding may as well happen where it is cheapest.
     */
    private final List<Event> pending = new ArrayList<>();

    /**
     * Numbers every recording, guarded by {@link #recordLock}, so a caller at a commit point can tell whether
     * a commit has already covered it.
     */
    private long recordedSequence;

    /**
     * Its summary counters are guarded by {@link #recordLock}. Its serializer is reached only under
     * {@link #commitLock}.
     */
    private final OutboundEventBuffer eventBuffer;

    // Atomic or volatile, so read and written without a lock.

    private final AtomicLong lastKnownPastTime = new AtomicLong(0);

    /**
     * Whether a commit is already queued, so a run of recordings past the threshold submits one task rather
     * than one per event.
     */
    private final AtomicBoolean commitScheduled = new AtomicBoolean(false);

    private final AtomicBoolean capacityExceeded = new AtomicBoolean(false);

    /** Whether the summarizer being full has already been reported, so it is logged once per run. */
    private final AtomicBoolean summaryContextsExceeded = new AtomicBoolean(false);

    EventCommitter(
            OutboundEventBuffer eventBuffer,
            EventStore store,
            int capacity,
            boolean commitOnCallerThread,
            ExecutorService commitExecutor,
            AtomicBoolean closed,
            AtomicLong lostEvents,
            AtomicLong droppedEvents,
            LDLogger logger
    ) {
        this.eventBuffer = eventBuffer;
        this.store = store;
        this.capacity = capacity;
        this.commitOnCallerThread = commitOnCallerThread;
        this.commitExecutor = commitExecutor;
        this.closed = closed;
        this.lostEvents = lostEvents;
        this.droppedEvents = droppedEvents;
        this.logger = logger;
    }

    /** As {@link DirectEventProcessor#recordEvaluationEvent}. */
    void recordEvaluation(
            LDContext context,
            String flagKey,
            int flagVersion,
            int variation,
            LDValue value,
            EvaluationReason reason,
            LDValue defaultValue,
            boolean requireFullEvent,
            Long debugEventsUntilDate
    ) {
        try {
            if (closed.get() || context == null) {
                return;
            }
            long timestamp = System.currentTimeMillis();
            boolean debug = shouldDebugEvent(debugEventsUntilDate);
            // Only built when something is kept beyond the counter. Most flags are not tracked, and for
            // those an evaluation is a counter increment with nothing left behind for the collector.
            // Built before the lock is taken, so that the critical section is only the writes.
            Event.FeatureRequest event = requireFullEvent || debug
                    ? new Event.FeatureRequest(timestamp, flagKey, context, flagVersion, variation, value,
                            defaultValue, reason, null, requireFullEvent, debugEventsUntilDate, false)
                    : null;
            Event debugEvent = debug ? event.toDebugEvent() : null;
            boolean contextsExceeded;
            boolean warnContextsExceeded;
            boolean needsCommit;
            synchronized (recordLock) {
                if (closed.get()) {
                    return;
                }
                recordedSequence++;
                contextsExceeded = !eventBuffer.summarize(timestamp, flagKey, flagVersion, variation,
                        value, defaultValue, context);
                // Claimed under the lock that the commit resets it under, so the warning belongs to the
                // run whose summarizer turned this evaluation away rather than to the next one.
                warnContextsExceeded = contextsExceeded
                        && summaryContextsExceeded.compareAndSet(false, true);
                if (requireFullEvent) {
                    addPending(event);
                }
                if (debugEvent != null) {
                    addPending(debugEvent);
                }
                needsCommit = pending.size() >= PENDING_COMMIT_THRESHOLD;
            }
            if (contextsExceeded) {
                reportContextsExceeded(warnContextsExceeded);
            }
            // Deliberately no commit point. An evaluation is expected to cost what a map lookup costs, and it
            // is usually the main thread doing it. The store writes these on its own thread once enough of them
            // have piled up, and the next event recorded at a commit point makes them durable along with itself.
            if (needsCommit) {
                scheduleCommitWherePersisting();
            }
        } catch (RuntimeException e) {
            // This runs on the application's thread, usually inside a flag evaluation, and an
            // analytics failure must not become the application's failure. Errors such as
            // OutOfMemoryError are left to propagate, so the application's crash reporting sees them.
            logUnexpectedError(e);
        }
    }

    /** Records an {@code identify} or {@code track} event, and commits at the commit point it is. */
    void recordAtCommitPoint(Event event) {
        commitAtCommitPoint(record(event));
    }

    /**
     * Holds an event for the next commit to encode, counting it as dropped if the SDK is already full.
     * <p>
     * Capacity is consulted before anything is encoded, so an event that will not be kept is never encoded.
     * That ordering is what bounds an application re-evaluating a tracked flag in a render loop: once the
     * limit is reached the cost of an evaluation falls back to its summary counter, however fast the loop runs.
     *
     * @return the recording's sequence number, for the commit point that follows it; 0 if it was refused
     */
    long record(Event event) {
        try {
            long sequence;
            boolean needsCommit;
            synchronized (recordLock) {
                // The close check that decides the outcome, as against the fast path the public record methods
                // take before building the event. The commit lifts the run out under this same lock, so testing
                // the flag here orders a record against close()'s final commit: either the event is in the list
                // before that commit takes it, or it is refused. Tested outside the lock the two interleave, and
                // an event can be left in a list that nothing will drain again.
                if (closed.get()) {
                    return 0;
                }
                sequence = ++recordedSequence;
                addPending(event);
                needsCommit = pending.size() >= PENDING_COMMIT_THRESHOLD;
            }
            if (needsCommit) {
                scheduleCommitWherePersisting();
            }
            return sequence;
        } catch (RuntimeException e) {
            // As in recordEvaluation: on the caller's thread, so a failure is logged, not thrown.
            logUnexpectedError(e);
            return 0;
        }
    }

    /**
     * Commits at a commit point, on the caller's thread or off it as the application asked.
     * <p>
     * Committing on the caller's thread is what lets {@code track} promise its event is on disk by the time
     * it returns. Scheduling it instead keeps the encoding and the disk I/O off that thread, and the event
     * is durable a moment later rather than immediately. When persistence is off that costs nothing at all,
     * since there is no disk for an early commit to reach.
     *
     * @param through the last recording the caller needs durable: its own, or for a flush everything so far
     */
    private void commitAtCommitPoint(long through) {
        try {
            if (commitOnCallerThread) {
                commitDurablyThrough(through);
            } else {
                scheduleCommitWherePersisting();
            }
        } catch (RuntimeException e) {
            // As in recordEvaluation: every commit point is on the caller's thread.
            logUnexpectedError(e);
        }
    }

    /**
     * Commits unless a finished commit already covers recording {@code through}, checked again once the lock
     * is held, since the commit a caller waited behind is the one most likely to have covered it.
     */
    private void commitDurablyThrough(long through) {
        if (committedSequence >= through) {
            return;
        }
        synchronized (commitLock) {
            if (committedSequence >= through) {
                return;
            }
            commitDurably();
        }
    }

    /** @return the sequence number of the last recording so far */
    private long lastRecordedSequence() {
        synchronized (recordLock) {
            return recordedSequence;
        }
    }

    /**
     * Queues the commit a full pending run or a commit point asks for, if there is a disk for it to reach.
     * <p>
     * Without persistence a commit makes nothing durable. All it would do is move the encoding into the
     * middle of the application's evaluations, competing with them for the CPU and splitting their
     * counters across summaries. Left alone, the run waits for the flush, which encodes it anyway, and
     * capacity still bounds how much is held.
     */
    private void scheduleCommitWherePersisting() {
        if (store.isPersisting()) {
            scheduleCommit();
        }
    }

    /** Queues a commit unless one is already queued, so a run of recordings asks for one rather than many. */
    private void scheduleCommit() {
        // Read before the compare-and-set, which takes the line for writing even when it fails, and fails for
        // every deferred track recorded while a commit is already queued.
        if (!commitScheduled.get() && commitScheduled.compareAndSet(false, true)) {
            try {
                commitExecutor.execute(guarded(this::runScheduledCommit));
            } catch (RuntimeException e) { // shut down by close(), whose own commit takes these events
                commitScheduled.set(false);
            }
        }
    }

    /**
     * Clears the scheduling flag before committing, not after, so events recorded while this runs can queue
     * a commit of their own rather than waiting for the next one to be triggered.
     * <p>
     * Does nothing once closed. {@link DirectEventProcessor#close()} sets the flag before its final commit,
     * which takes everything recorded up to then, and nothing is recorded after it. A commit reaching here
     * later could only run against a store that has been released.
     */
    private void runScheduledCommit() {
        commitScheduled.set(false);
        if (closed.get()) {
            return;
        }
        commitDurably();
    }

    /**
     * The commit point {@link DirectEventProcessor#flush()} and {@link DirectEventProcessor#blockingFlush()}
     * start with, before anything is handed to the events thread.
     * <p>
     * Written on the caller's thread wherever events persist, not only where the application asked for
     * that as it does for {@code track} and {@code identify}. A flush is what an application calls when it
     * is about to end the process on purpose, and the process may end before a commit it only queued
     * has run. Where nothing persists, a commit makes nothing durable, and is left to the delivery.
     * <p>
     * It does not wait for the events thread, which can be held by a post or by a retry long after the
     * caller has moved on. The delivery commits again when it runs, but that is too late to be the only
     * commit.
     */
    void commitBeforeDelivery() {
        try {
            if (commitOnCallerThread || store.isPersisting()) {
                commitDurablyThrough(lastRecordedSequence());
            }
        } catch (RuntimeException e) {
            // As in recordEvaluation: every commit point is on the caller's thread.
            logUnexpectedError(e);
        }
    }

    /**
     * The commit point {@link DirectEventProcessor#flushAsync()} starts with: the same commit as
     * {@link #commitBeforeDelivery}, queued on the commit executor rather than written on the caller's thread.
     * <p>
     * A caller of {@code flushAsync} has a deadline, which is how {@code flushAndWait} bounds the whole call,
     * and it can only enforce one once it holds the future. Each step of a commit can block for as long as
     * the device takes: on {@link #commitLock} behind a commit or a delivery that stalled on the disk, on the
     * store's I/O lock, and on its own writing. Committed here, any of them ran before the deadline could
     * apply, so a stalled flash write outlasted the timeout of the crash handler on its way out.
     * <p>
     * Queued rather than left to the delivery, because the events thread can be held by a post or by a
     * retry long after the caller has stopped waiting, and {@code flushAndWait} is for a caller that is about
     * to be killed. The commit executor is not held by the network. The future still answers for the write:
     * the delivery commits again before it sends, so a {@code true} means the events were written first. A
     * commit that outlasts the caller's wait is left to finish, as {@code close()} leaves its own.
     */
    void queueCommitBeforeDelivery() {
        try {
            if (commitOnCallerThread || store.isPersisting()) {
                long through = lastRecordedSequence();
                commitExecutor.execute(guarded(() -> {
                    // As in runScheduledCommit: close() writes everything recorded before it with its own
                    // commit, and a commit reaching here later could only run against a released store.
                    if (!closed.get()) {
                        commitDurablyThrough(through);
                    }
                }));
            }
        } catch (RuntimeException e) {
            // Shut down by close(), whose own commit takes these events; or as in recordEvaluation,
            // a failure on the caller's thread is logged rather than thrown.
            if (!closed.get()) {
                logUnexpectedError(e);
            }
        }
    }

    /**
     * Encodes everything recorded since the last commit and makes it survive the process.
     * <p>
     * This is the only place events are serialized. It runs on the commit executor when the pending run
     * reaches {@link #PENDING_COMMIT_THRESHOLD}, and on the caller's thread for {@code flush}, where the
     * caller has asked to wait for exactly this.
     * <p>
     * The evaluations counted since the last commit are written too, because a summary is only worth having
     * if it covers the evaluations that led up to whatever is about to happen.
     */
    void commitDurably() {
        synchronized (commitLock) {
            List<Event> run;
            List<EventSummarizer.EventSummary> summaries;
            long through;
            // Both taken at once, so that an evaluation's counter and its full event are staged by the
            // same commit. Taken separately, a commit landing between the two appends one evaluation makes
            // stages the counter and leaves the event for the next one -- or, at close, for none at all.
            synchronized (recordLock) {
                through = recordedSequence;
                run = pending.isEmpty() ? Collections.emptyList() : new ArrayList<>(pending);
                pending.clear();
                summaries = eventBuffer.takeSummaries();
                summaryContextsExceeded.set(false);
                // Reserved in the same critical section the run leaves pending in, so capacity counts
                // it in one place or the other throughout the encoding. Each staged event uses up its
                // reservation as it arrives, so none is counted twice either.
                store.reserve(run.size());
            }
            try {
                try {
                    stageRun(run);
                } finally {
                    store.releaseReservations();
                }
                stageSummaries(summaries);
            } catch (Throwable t) {
                // The run and the counters have left pending, and nothing puts them back, so a flush must not
                // answer true over them.
                lostEvents.incrementAndGet();
                throw t;
            }
            if (eventBuffer.takeSerializationFailure()) {
                lostEvents.incrementAndGet();
            }
            store.commit();
            committedSequence = through;
        }
    }

    /** What {@link #commitAndCloseBatch()} did. */
    static final class Close {
        /** The loss count as of the close, which is as far as the delivery can answer for. */
        final long lostEventsMark;
        /** Whether the close took every committed event into a batch. */
        final boolean closedEverything;

        Close(long lostEventsMark, boolean closedEverything) {
            this.lostEventsMark = lostEventsMark;
            this.closedEverything = closedEverything;
        }
    }

    /**
     * Commits everything accepted so far and closes it into a batch to deliver, taking the loss
     * count as of that close.
     * <p>
     * Both under {@link #commitLock}, for two reasons. A commit landing between the two would stage
     * events into the log this batch is about to close. That splits what one commit staged across
     * two payloads: an evaluation's counter in this batch and its event in the next. And the mark
     * has to be the count as of the close, because that is what this delivery can answer for: a loss
     * after it is one whose events were never in this batch.
     * <p>
     * A flush, requested or periodic, has usually just committed before it delivers, so this commit often
     * finds nothing to do. It cannot be the one left out: that earlier commit is only where events persist,
     * and the retry and the final delivery at close reach here without it.
     *
     * @return the loss count as of the close, and whether the close took every committed event into a
     *   batch; both under the lock, so they cover this close and nothing a later commit does
     */
    Close commitAndCloseBatch() {
        synchronized (commitLock) {
            commitDurably();
            store.closeBatch();
            reportVanished(store.takeVanishedLogEvents());
            return new Close(lostEvents.get(), !store.holdsUnclosedEvents());
        }
    }

    /**
     * {@link #commitAndCloseBatch()} for a pass that found an earlier batch blocked, and so closes nothing.
     * The mark is as of this commit, the last point anything the caller recorded was staged.
     *
     * @return the loss count as of the commit
     */
    long commitLeavingTheLogOpen() {
        synchronized (commitLock) {
            commitDurably();
            // A commit that fills the log closes it, and can find it gone.
            reportVanished(store.takeVanishedLogEvents());
            return lostEvents.get();
        }
    }

    /** Counts as lost the events of an open log a close found gone. */
    private void reportVanished(int events) {
        if (events <= 0) {
            return;
        }
        logger.error("{} stored event(s) were deleted before they could be sent", events);
        lostEvents.addAndGet(events);
    }

    /**
     * Commits everything recorded so far for close(), waiting for it no longer than the budget allows.
     * <p>
     * On the commit executor rather than the caller's thread, because each step of a commit can block
     * for as long as the device takes. {@link #commitLock} may be held by a commit or a delivery that
     * stalled on the disk, and the store's I/O lock by the disk I/O it stalled on. This commit's own
     * writing can stall the same way. Committing on the caller's thread was bounded by none of them, so
     * a stalled flash write turned into an ANR on the main thread.
     * <p>
     * A commit that outlasts the budget is left to finish, for the same reason as the delivery. The
     * executor thread is a daemon, and returning from close() does not end the process. Writing cut off
     * partway leaves a torn frame that the next run reads past, not events lost.
     *
     * @param budgetMillis the whole of close()'s budget, for the warning
     * @return the commit, for the store's release to wait on, or null if it could not be queued
     */
    Future<?> commitWithinBudget(long deadlineNanos, long budgetMillis) {
        Future<?> commit;
        try {
            commit = commitExecutor.submit(guarded(this::commitDurably));
        } catch (RuntimeException e) { // shut down under us; the final delivery still commits first
            return null;
        }
        try {
            commit.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            logger.warn("Gave up waiting for the final event commit after {}ms;" +
                    " it continues in the background", budgetMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logUnexpectedError(e.getCause() == null ? e : e.getCause());
        }
        return commit;
    }

    /** Stops the commit thread taking more. Commits already queued there still run, and find the processor closed. */
    void shutdown() {
        commitExecutor.shutdown();
    }

    /**
     * Encodes a run of events as one run and stages the bytes.
     * <p>
     * The run was taken under {@link #recordLock} and is encoded outside it, so recording does not wait on
     * the encoder. Staging bypasses capacity because the decision to keep these events was already made in
     * {@link #record}, and refusing them here would drop events the SDK has counted as accepted. The
     * reservation taken with the run is what keeps them counted until they are staged. The byte capacity
     * can still refuse one, since only its encoded size says whether it fits, and an event refused then is
     * lost rather than dropped.
     * <p>
     * Requires {@link #commitLock}: two threads staging separate runs would stage them in whichever order
     * they finished encoding, which is not the order they were recorded in.
     */
    private void stageRun(List<Event> run) {
        for (byte[] serialized : eventBuffer.serializeAll(run)) {
            if (!store.stageReserved(serialized)) {
                reportRefusedByStore("event", serialized);
            }
        }
    }

    /**
     * Turns the evaluation counters already taken into summary events in the store.
     */
    private void stageSummaries(List<EventSummarizer.EventSummary> summaries) {
        for (byte[] summary : eventBuffer.serializeSummaries(summaries)) {
            // Bypassing capacity: a summary is not a new event, it is the record of evaluations already
            // counted, and dropping it would lose all of them at once. Only the byte capacity, which keeps
            // summaries staged while delivery keeps failing from growing without bound, can refuse it.
            if (!store.stage(summary, true)) {
                reportRefusedByStore("summary event", summary);
            }
        }
    }

    /**
     * Records the loss of something the store would not take. Capacity is bypassed on both paths, so
     * what is left is a frame too large to store, or one the byte capacity has no room for, and the SDK
     * had already accepted it either way.
     */
    private void reportRefusedByStore(String what, byte[] serialized) {
        if (serialized.length > EventLogFormat.MAX_FRAME_SIZE) {
            logger.error("Dropping {} of {} bytes, too large to store", what, serialized.length);
        } else {
            logger.error("Dropping {} of {} bytes, which the event store has no room for", what,
                    serialized.length);
        }
        lostEvents.incrementAndGet();
    }

    /**
     * Holds one event for the next flush, unless it was sampled out or there is no room. Requires
     * {@link #recordLock}.
     */
    private void addPending(Event event) {
        // Ahead of the capacity check, because an event the SDK was never going to send is not a
        // loss and must not be counted as one. Sampling and capacity are different reasons not to
        // keep an event, and only the second is one the SDK owes anyone a count of.
        if (!Sampler.shouldSample(event.getSamplingRatio())) {
            return;
        }
        // The bytes are checked here too, before the event is encoded, so that one there is no room for
        // costs nothing; whether it fits is decided when it is staged.
        if (!store.admitsEvent(pending.size(), capacity)) {
            if (capacityExceeded.compareAndSet(false, true)) {
                logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
            }
            droppedEvents.incrementAndGet();
            return;
        }
        // Read before written: a write on every event would contend for the line with nothing to change.
        if (capacityExceeded.get()) {
            capacityExceeded.set(false);
        }
        pending.add(event);
    }

    /**
     * Counts an evaluation the summarizer turned away, which it does when counting it would have meant
     * holding a context beyond the configured capacity.
     * <p>
     * A refused evaluation is a loss in the same sense a refused event is -- nothing later reconstructs
     * a counter -- so it is reported the same way, through the dropped count diagnostics carry.
     */
    private void reportContextsExceeded(boolean warn) {
        // Warned once per delivery run rather than once per process: the summarizer's contexts are
        // cleared with each run, so a later overflow is a new one worth hearing about.
        if (warn) {
            logger.warn("Exceeded the number of contexts that can be summarized at once." +
                    " Increase capacity to avoid dropping evaluations.");
        }
        droppedEvents.incrementAndGet();
    }

    /**
     * Moves the threshold forwards only. Analytics and diagnostic responses are handled on separate
     * threads, so a plain assignment would let an older reading of the service clock overwrite a
     * newer one and keep debug events alive past the date the service set. A loop rather than
     * {@code accumulateAndGet}, which needs API 24.
     */
    void recordPastTime(long timeFromServer) {
        long known;
        do {
            known = lastKnownPastTime.get();
            if (timeFromServer <= known) {
                return;
            }
        } while (!lastKnownPastTime.compareAndSet(known, timeFromServer));
    }

    /**
     * A debug event is emitted until the date the service gave us passes. We compare against the
     * last date we know to be in the past according to the service as well as the device clock, so
     * that a device whose clock is wrong errs on the side of stopping sooner.
     */
    private boolean shouldDebugEvent(Long debugEventsUntilDate) {
        if (debugEventsUntilDate == null || debugEventsUntilDate <= 0) {
            return false;
        }
        return debugEventsUntilDate > lastKnownPastTime.get()
                && debugEventsUntilDate > System.currentTimeMillis();
    }

    private Runnable guarded(Runnable task) {
        return EventUtil.guarded(logger, task);
    }

    private void logUnexpectedError(Throwable e) {
        EventUtil.logUnexpectedError(logger, e);
    }
}
