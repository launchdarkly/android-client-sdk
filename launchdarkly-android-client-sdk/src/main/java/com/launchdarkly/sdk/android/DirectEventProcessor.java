package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.internal.events.DiagnosticEvent;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventSender;
import com.launchdarkly.sdk.internal.events.EventSummarizer;
import com.launchdarkly.sdk.internal.events.Sampler;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Android SDK's analytics event processor.
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
 * or on the caller's thread at a commit point, one at a time under {@link #commitLock}. Every delivery runs
 * on {@link #scheduler}, a single thread: a requested one, the periodic one, the retry after a failure, and
 * the final one {@code close()} queues. So no two deliveries of this processor overlap, and the final one
 * waits behind a post or retry already running there, within the budget {@code close()} gives it.
 * Diagnostic events go out on a thread of their own.
 * <p>
 * Overlap is across processes. Two processes of one application read the same directory, so both can send
 * one stored batch. They send it under the same payload ID, and LaunchDarkly discards the repeat rather than
 * counting the events twice.
 * <p>
 * Flag values come first. Where events persist, what a previous run left waits at launch for the first flag
 * request, and the deliveries this processor starts on its own send one batch at a time, giving way to any flag
 * request in flight. {@link ConnectivityManager} says what the flag data source is doing through
 * {@link FlagRequestListener}. A delivery the application asks for is never held.
 */
final class DirectEventProcessor implements EventProcessor, FlagRequestListener {
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

    /**
     * How long {@link #close()} spends waiting for the final delivery before it gives up and returns.
     * <p>
     * {@code close()} runs on the caller's thread, which for an application shutting down is usually the
     * main one, so this has to stay well inside the five seconds Android allows before an unanswered
     * input event becomes an ANR. Two seconds is where the return on waiting longer falls off. The
     * events client keeps pooled connections for only five seconds against a thirty-second flush
     * interval, so this post nearly always pays a full DNS, TCP and TLS handshake. That is about three
     * round trips, which two seconds covers up to a round-trip time of roughly 600 ms. A network slower
     * than that is one the post is likely to fail on anyway.
     * <p>
     * Overshooting the budget is cheaper than it looks, because the delivery is not canceled when the
     * budget expires; see {@link #close()}.
     */
    static final long DEFAULT_CLOSE_BUDGET_MILLIS = 2_000;

    /**
     * The first wait of the backoff after a failure, as the other SDKs wait for their one retry. A flush
     * waiting on the delivery that failed sits out this wait and hears the retry's outcome; any longer
     * wait it does not, and is told the delivery failed.
     */
    static final long RETRY_DELAY_MILLIS = 1_000;

    // Set in the constructor and never changed.

    private final EventStore store;
    private final EventSender diagnosticEventSender;
    private final AnalyticsEventSender analyticsEventSender;
    /** When the next post may go, after a failure. Consulted before every post, and only there. */
    private final DeliveryRetryState retryState;
    private final URI eventsUri;
    private final DiagnosticStore diagnosticStore;
    private final long flushIntervalMillis;
    private final long diagnosticRecordingIntervalMillis;
    private final long closeBudgetMillis;
    private final ScheduledExecutorService scheduler;
    /**
     * Where a commit no caller waits for runs: a thread of its own, not {@link #scheduler}.
     * <p>
     * The scheduler also delivers, and one delivery can hold it for as long as two network timeouts. A
     * commit queued behind that would leave a {@code DEFERRED} {@code track} in memory for all of it, and
     * let the pending run fill toward capacity meanwhile.
     */
    private final ExecutorService commitExecutor;
    private final ExecutorService diagnosticExecutor;
    private final LDLogger logger;

    /**
     * How many events the SDK will hold in total, across {@link #pending} and the store.
     */
    private final int capacity;

    /**
     * Whether a commit point encodes and writes before returning, rather than queuing that work.
     */
    private final boolean commitOnCallerThread;

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
     * payloads. A {@link #close()} landing between them delivers the counter and then refuses the
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

    // Guarded by stateLock.

    /**
     * Guards {@link #flushTask} and {@link #diagnosticTask}, and every change to {@link #inBackground}
     * and {@link #offline}, so the tasks are rescheduled in the order the changes happen. Those two
     * are atomic so they can be read without it.
     */
    private final Object stateLock = new Object();
    private ScheduledFuture<?> flushTask;
    private ScheduledFuture<?> diagnosticTask;
    private final AtomicBoolean inBackground;
    private final AtomicBoolean offline;

    // Guarded by submitLock.

    /**
     * Guards the handover from running to shut down: held for the length of a submit, and by
     * {@link #close()} while it queues the release of each thread's resources and stops the
     * executors accepting work. Nothing blocking happens under it.
     */
    private final Object submitLock = new Object();

    /** Set under {@link #submitLock} once close() has queued the release of those resources. */
    private boolean shuttingDown = false;

    /**
     * The retry queued after a delivery failed in a way that may pass, guarded by {@link #submitLock}.
     * One at a time: it retries every batch still waiting, so a second would only repeat it.
     */
    private ScheduledFuture<Boolean> pendingRetry;

    /**
     * Set for the length of close()'s final delivery, which may post while the backoff would not allow it.
     * Only ever read and written on the scheduler thread, where every delivery runs.
     */
    private boolean postingThroughTheBackoff;

    // Guarded by flushLock.

    /**
     * Guards {@link #pendingFlush} and {@link #pendingFlushAnswersACaller}. Taken on a caller's thread and
     * on the delivery thread, never while holding {@link #recordLock}, and nothing blocking happens under it.
     */
    private final Object flushLock = new Object();

    /**
     * The delivery that is queued but has not started, which a flush request arriving now can wait
     * on instead of queuing another. Null while nothing is queued, and cleared again as the queued
     * delivery begins, which is the point past which it can no longer speak for what is recorded.
     */
    private LDAwaitFuture<Boolean> pendingFlush;

    /**
     * Whether a {@link #flushAsync()} caller, who is there to hear the outcome, is waiting on
     * {@link #pendingFlush}, as opposed to only callers that discard it.
     */
    private boolean pendingFlushAnswersACaller;

    // Atomic or volatile, so read and written without a lock.

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean diagnosticInitSent = false;
    private final AtomicLong lastKnownPastTime = new AtomicLong(0);
    /**
     * Whether a previous run of the application left events behind.
     * <p>
     * Those events are already as old as whatever happened to that run, so they are sent once the launch
     * gate opens rather than at the next interval. The application in front of the user has moved on; an
     * error report about a crash is not worth much thirty seconds late, and the process might not live that
     * long either.
     */
    private final AtomicBoolean hasEventsFromPreviousRun = new AtomicBoolean(false);

    private final FlagTraffic.Timings flagTimings;

    /**
     * Whether events give way to flag values: only where the application asked for persistence, like the
     * rest of what persistence brings. Without it there is no previous run to hold back, and delivery is
     * what it was before persistence: no launch gate, no pacing, and nothing heard about flag requests.
     */
    private final boolean givesWayToFlags;

    /** Only where events give way to flags; null otherwise, so that nothing of it runs. */
    private final FlagTraffic flagTraffic;

    /**
     * Whether the launch gate has opened. It opens once the floor has passed since the SDK first went online,
     * and the first flag request has settled, or there was none, or the cap has passed too. Until then neither
     * a previous run's events nor the diagnostic init event are sent. Open from the start where events do not
     * give way. Only set on the scheduler thread.
     */
    private volatile boolean launchGateOpen;

    /** Set once, under {@link #stateLock}, when the SDK first goes online; the gate counts from then. */
    private volatile boolean wentOnline;
    private volatile long wentOnlineNanos;

    /**
     * Whether a delivery someone asked for is queued, which a paced delivery holding the scheduler stops
     * pacing for. Mirrors {@code pendingFlush != null}, written under {@link #flushLock}.
     */
    private volatile boolean requestedDeliveryQueued;

    // Only read and written on the scheduler thread.

    /** Whether the delivery running is one this processor started on its own, and so paced. */
    private boolean pacedPass;
    private int pacedPostsInPass;
    private long lastPacedPostEndedNanos;

    /**
     * Whether a commit is already queued, so a run of recordings past the threshold submits one task rather
     * than one per event.
     */
    private final AtomicBoolean commitScheduled = new AtomicBoolean(false);

    private final AtomicBoolean capacityExceeded = new AtomicBoolean(false);

    /** Whether the summarizer being full has already been reported, so it is logged once per run. */
    private final AtomicBoolean summaryContextsExceeded = new AtomicBoolean(false);

    private final AtomicLong droppedEvents = new AtomicLong(0);

    /**
     * True while a diagnostic event is on its way to the service, so that a later one is dropped
     * rather than queued behind it.
     */
    private final AtomicBoolean diagnosticPostInFlight = new AtomicBoolean(false);

    /**
     * How many events the SDK accepted and then dropped for good.
     * <p>
     * A batch the service refuses is kept and retried, so it is not a loss, unless the refusal is a
     * second refusal of the payload in a row. Otherwise what is lost is what the store never received: an event or a
     * summary that could not be serialized when it was committed, or that serialized too large to
     * store. A delivery that
     * finds nothing to send cannot tell those apart from events that arrived, so without this it
     * would report success for them.
     * <p>
     * A count rather than a flag because a delivery can answer long after it ran: one that met a
     * retryable failure answers only when its retry does, and another delivery can come and go in
     * between. Each delivery takes the count as it closes its batch and answers only for the losses
     * up to that mark, so it is never told about a loss that happened after it had taken everything
     * it could speak for. Only the order of two values ever matters, never the difference between
     * them, so a failure that lost several events need only move it once.
     * <p>
     * Atomic because commits run on the commit executor and on callers' threads, and a caller
     * waiting behind a retry is answered from a pooled thread.
     */
    private final AtomicLong lostEvents = new AtomicLong(0);

    /**
     * The loss count through which losses belong to a delivery some {@link #flushAsync()} caller waits on,
     * so that each loss is told to one caller: the first whose delivery passed it.
     * <p>
     * Claimed as each pass ends, in the order deliveries run, rather than as each answers. A delivery
     * waiting on a retry answers after the one behind it may have, and claiming on answering would hand
     * that later delivery the losses before the earlier one's mark, leaving the flush whose delivery lost
     * the events answering true once its retry works. A delivery nobody waits on claims nothing, so what
     * it passed is told to the next caller instead of to no one.
     * <p>
     * Only read and written on the scheduler thread, which runs every delivery.
     */
    private long claimedLostEvents;

    /**
     * How many refusals of the payload in a row drop a batch: a 400, 413, 415 or 422, in any mix
     * ({@link LDUtil#isEventPayloadRefused}). Each says the service will not take this body, which posting
     * it again will not change, and a kept batch is the oldest and so the next posted: kept for good, it
     * would hold every batch behind it. Two rather than one, so one from a proxy or an incident costs nothing.
     */
    static final int BAD_REQUESTS_BEFORE_DROPPING = 2;

    // The batch that last had its payload refused and how many times in a row: delivery goes oldest first
    // and stops at a kept failure, so the next post is that batch's unless capacity dropped it. Held only in
    // memory, so a new run of the application counts again.
    private final Object badRequestLock = new Object();
    private String badRequestPayloadId;
    private int badRequestsInARow;

    DirectEventProcessor(
            OutboundEventBuffer eventBuffer,
            EventStore store,
            EventSender diagnosticEventSender,
            AnalyticsEventSender analyticsEventSender,
            DeliveryRetryState retryState,
            URI eventsUri,
            DiagnosticStore diagnosticStore,
            int capacity,
            boolean commitOnCallerThread,
            long flushIntervalMillis,
            long diagnosticRecordingIntervalMillis,
            long closeBudgetMillis,
            FlagTraffic.Timings flagTimings,
            boolean initiallyInBackground,
            boolean initiallyOffline,
            ScheduledExecutorService scheduler,
            ExecutorService commitExecutor,
            ExecutorService diagnosticExecutor,
            LDLogger logger
    ) {
        this.eventBuffer = eventBuffer;
        this.store = store;
        this.commitOnCallerThread = commitOnCallerThread;
        this.diagnosticEventSender = diagnosticEventSender;
        this.analyticsEventSender = analyticsEventSender;
        this.retryState = retryState;
        this.eventsUri = eventsUri;
        this.diagnosticStore = diagnosticStore;
        this.capacity = capacity;
        this.flushIntervalMillis = flushIntervalMillis;
        this.diagnosticRecordingIntervalMillis = diagnosticRecordingIntervalMillis;
        this.closeBudgetMillis = closeBudgetMillis;
        this.flagTimings = flagTimings;
        this.givesWayToFlags = store.isPersisting();
        this.flagTraffic = givesWayToFlags ? new FlagTraffic() : null;
        this.launchGateOpen = !givesWayToFlags;
        this.scheduler = scheduler;
        this.commitExecutor = commitExecutor;
        this.diagnosticExecutor = diagnosticExecutor;
        this.logger = logger;
        this.inBackground = new AtomicBoolean(initiallyInBackground);
        this.offline = new AtomicBoolean(initiallyOffline);

        // Off the calling thread: this is on the path of LDClient.init, and it reads the filesystem. Only where
        // events persist, since memory holds nothing from a previous run.
        if (store.isPersisting()) {
            submit(this::recoverEventsFromPreviousRun);
        }

        synchronized (stateLock) {
            updateScheduledTasks(initiallyInBackground, initiallyOffline);
        }
    }

    private void recoverEventsFromPreviousRun() {
        boolean found;
        // Below the threads loading flags, which matter more at launch than what a previous run left.
        Thread thread = Thread.currentThread();
        int priority = thread.getPriority();
        thread.setPriority(Thread.MIN_PRIORITY);
        try {
            // Lost as a damaged batch is, and told the same way: to the next flush a caller waits on.
            lostEvents.addAndGet(store.preparePayloadsFromInterruptedLogs());
            reportDiscarded(store.discardExpired());
            found = !store.pendingBatches().isEmpty();
        } finally {
            thread.setPriority(priority);
        }
        if (!found) {
            return;
        }
        // Raised before the gate and offline are read, and claimed by whichever side sees both: opening
        // the gate, and going online after it, write their state and then claim the flag, so at least one
        // of the two finds the other's write, and the claim makes it exactly one. Reading them first left
        // a window where the other side found no flag yet and this then found the SDK still offline, and
        // neither sent them.
        hasEventsFromPreviousRun.set(true);
        if (launchGateOpen && !offline.get() && !isStopped()
                && hasEventsFromPreviousRun.compareAndSet(true, false)) {
            deliverPayload();
        }
    }

    @Override
    public void recordEvaluationEvent(
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
            if (isStopped() || context == null) {
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

    @Override
    public void recordIdentifyEvent(LDContext context) {
        if (isStopped() || context == null) {
            return;
        }
        commitAtCommitPoint(record(new Event.Identify(System.currentTimeMillis(), context)));
    }

    @Override
    public void recordCustomEvent(LDContext context, String eventKey, LDValue data, Double metricValue) {
        if (isStopped() || context == null) {
            return;
        }
        commitAtCommitPoint(record(new Event.Custom(System.currentTimeMillis(), eventKey, context, data,
                metricValue)));
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
            // As in recordEvaluationEvent: on the caller's thread, so a failure is logged, not thrown.
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
            // As in recordEvaluationEvent: every commit point is on the caller's thread.
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
     * Does nothing once closed. {@link #close()} sets the flag before its final commit, which takes
     * everything recorded up to then, and nothing is recorded after it. A commit reaching here later could
     * only run against a store that has been released.
     */
    private void runScheduledCommit() {
        commitScheduled.set(false);
        if (closed.get()) {
            return;
        }
        commitDurably();
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
    private void commitDurably() {
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
                stageRun(run);
            } finally {
                store.releaseReservations();
            }
            stageSummaries(summaries);
            if (eventBuffer.takeSerializationFailure()) {
                lostEvents.incrementAndGet();
            }
            store.commit();
            committedSequence = through;
        }
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
     * Records a batch lost for good. The delivery that lost it fails, but a delivery nobody waits on has no
     * caller to tell: the periodic one, a {@code flush()}, or the one going online starts. So the loss is
     * also counted for the next {@link #flushAsync()} to hear about. A caller waiting on this delivery can
     * then hear about it from the next one too.
     */
    private void reportBatchLost(EventStore.Batch batch) {
        lostEvents.addAndGet(batch.eventCount);
    }

    /**
     * Records stored events discarded for being older than the maximum age.
     * <p>
     * Counted as dropped rather than lost: a flush is about the caller's events, and the ones discarded
     * are long past anything a caller is waiting on.
     */
    private void reportDiscarded(int events) {
        if (events <= 0) {
            return;
        }
        logger.debug("Discarded {} stored event(s) older than the maximum age", events);
        droppedEvents.addAndGet(events);
    }

    /**
     * Claims the losses up to a mark for the delivery that just ended its pass, which a caller waits on.
     * Runs on the scheduler thread.
     *
     * @param mark the loss count as of the close of that delivery's batch
     * @return true if a loss at or before the mark belonged to no earlier delivery a caller waited on
     */
    private boolean claimEventsLost(long mark) {
        if (mark <= claimedLostEvents) {
            return false;
        }
        claimedLostEvents = mark;
        return true;
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
     * @return the number of events dropped for capacity since this was last called, counting
     *   evaluations the summarizer turned away
     */
    long getAndClearDroppedCount() {
        return droppedEvents.getAndSet(0);
    }

    @Override
    public void setInBackground(boolean inBackground) {
        synchronized (stateLock) {
            if (this.inBackground.getAndSet(inBackground) == inBackground) {
                return;
            }
            updateScheduledTasks(inBackground, offline.get());
        }
    }

    @Override
    public void setOffline(boolean offline) {
        synchronized (stateLock) {
            if (this.offline.getAndSet(offline) == offline) {
                return;
            }
            updateScheduledTasks(inBackground.get(), offline);
        }
        wakePacedDelivery();
    }

    @Override
    public boolean givesWayToFlags() {
        return givesWayToFlags;
    }

    @Override
    public void flagRequestStarted() {
        if (flagTraffic != null) {
            flagTraffic.started();
        }
    }

    @Override
    public void flagRequestSettled(Outcome outcome) {
        if (flagTraffic == null) {
            return;
        }
        flagTraffic.settled(outcome);
        if (!launchGateOpen && wentOnline) {
            submit(this::checkLaunchGate);
        }
    }

    /** Has a paced delivery waiting for its turn look again at whether it should stop waiting. */
    private void wakePacedDelivery() {
        if (flagTraffic != null) {
            flagTraffic.wake();
        }
    }

    @Override
    public void flush() {
        if (isStopped()) {
            return;
        }
        commitBeforeDelivery();
        queueDelivery(false);
    }

    @Override
    public void blockingFlush() {
        if (isStopped()) {
            return;
        }
        commitBeforeDelivery();
        Future<Boolean> delivery = queueDelivery(false);
        try {
            delivery.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logUnexpectedError(e.getCause() == null ? e : e.getCause());
        }
    }

    @Override
    public Future<Boolean> flushAsync() {
        if (isStopped()) {
            return new LDSuccessFuture<>(false);
        }
        queueCommitBeforeDelivery();
        return queueDelivery(true);
    }

    /**
     * The commit point {@link #flush()} and {@link #blockingFlush()} start with, before anything is
     * handed to the events thread.
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
    private void commitBeforeDelivery() {
        try {
            if (commitOnCallerThread || store.isPersisting()) {
                commitDurablyThrough(lastRecordedSequence());
            }
        } catch (RuntimeException e) {
            // As in recordEvaluationEvent: every commit point is on the caller's thread.
            logUnexpectedError(e);
        }
    }

    /**
     * The commit point {@link #flushAsync()} starts with: the same commit as {@link #commitBeforeDelivery},
     * queued on the commit executor rather than written on the caller's thread.
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
     * commit that outlasts the caller's wait is left to finish, as {@link #close()} leaves its own.
     */
    private void queueCommitBeforeDelivery() {
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
            // Shut down by close(), whose own commit takes these events; or as in recordEvaluationEvent,
            // a failure on the caller's thread is logged rather than thrown.
            if (!closed.get()) {
                logUnexpectedError(e);
            }
        }
    }

    /**
     * Queues a delivery, or hands back one that is already queued and has not started.
     * <p>
     * A delivery that has not started yet will send everything recorded before it starts. That
     * includes the caller's events, so waiting on it is as good as queuing a new one. Without this,
     * flushes arriving faster than a post completes each queue their own, and the one that matters --
     * the {@code flushAndWait} at shutdown -- waits behind all of them.
     *
     * @param answersACaller true if the caller will hear the outcome, so that the delivery reports
     *   any events lost up to the batch it closes, and false if the caller discards it
     */
    private Future<Boolean> queueDelivery(boolean answersACaller) {
        LDAwaitFuture<Boolean> result;
        synchronized (flushLock) {
            if (pendingFlush != null) {
                pendingFlushAnswersACaller |= answersACaller;
                return pendingFlush;
            }
            result = new LDAwaitFuture<>();
            if (submit(() -> runDelivery(result)) == null) {
                // Shutting down, so there is no thread left to deliver on and nothing will be sent.
                return new LDSuccessFuture<>(false);
            }
            pendingFlush = result;
            pendingFlushAnswersACaller = answersACaller;
            requestedDeliveryQueued = true;
        }
        // A paced delivery holding the scheduler sends the rest back to back, so this one is not held behind it.
        wakePacedDelivery();
        return result;
    }

    /**
     * Runs one delivery on behalf of every flush request that joined it, and tells them all how it
     * went.
     * <p>
     * A caller who will hear the answer is told no if the delivery fails. It is also told no if events
     * were dropped for good up to the batch this delivery closed and no earlier delivery a caller waited
     * on had passed them, because a delivery that finds nothing to send is not evidence that everything
     * recorded arrived.
     */
    private void runDelivery(LDAwaitFuture<Boolean> result) {
        boolean answersACaller = false;
        synchronized (flushLock) {
            // Requests arriving from here on need a delivery of their own: this one is about to take
            // the buffer, and what it takes is all it can speak for.
            if (pendingFlush == result) {
                pendingFlush = null;
                requestedDeliveryQueued = false;
                answersACaller = pendingFlushAnswersACaller;
                pendingFlushAnswersACaller = false;
            }
        }
        DeliveryOutcome outcome;
        try {
            outcome = commitAndDeliverReportingOutcome();
        } catch (Throwable t) {
            // Caught here rather than left to guarded(), because a caller is waiting on the future
            // and completing it matters more than the stack reaching the executor.
            logUnexpectedError(t);
            outcome = DeliveryOutcome.CLOSED_NOTHING;
        }
        // Claimed before the next delivery can run, however long this one's retry takes to answer.
        boolean lostEvents = answersACaller && claimEventsLost(outcome.lostEventsMark);
        if (outcome.retry == null) {
            answer(result, outcome.delivered, lostEvents);
        } else {
            completeWhenRetryEnds(result, outcome, lostEvents);
        }
    }

    /**
     * Answers the waiters when the queued retry does, rather than telling them a batch failed when
     * it is about to be sent again. Waiting happens on a pooled thread because the retry runs a
     * second from now on this one, which must be free to take it.
     */
    private void completeWhenRetryEnds(LDAwaitFuture<Boolean> result, DeliveryOutcome outcome,
                                       boolean lostEvents) {
        LDAwaitFuture<Boolean> retried = LDFutures.fromFuture(outcome.retry);
        retried.addListener(() -> {
            boolean delivered = false;
            try {
                delivered = Boolean.TRUE.equals(retried.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // Reported as not delivered.
            }
            // The losses the delivery claimed, not any since: a loss during the wait belongs to
            // whichever delivery closes the batch after it, not to this one.
            answer(result, delivered, lostEvents);
        });
    }

    /**
     * Completes a delivery's future, telling its callers no if this delivery failed or lost events the
     * SDK accepted.
     */
    private void answer(LDAwaitFuture<Boolean> result, boolean delivered, boolean lostEvents) {
        result.set(delivered && !lostEvents);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (stateLock) {
            updateScheduledTasks(inBackground.get(), offline.get());
        }
        // A paced delivery holding the scheduler sends the rest back to back, ahead of the final one.
        wakePacedDelivery();
        // One budget for the whole of close(), which runs on the caller's thread. For an application
        // shutting down that is usually the main one, so it has to stay well inside the five seconds
        // Android allows before an unanswered input event becomes an ANR. The budget bounds the caller, not
        // the work: whatever outlasts it is left to finish on its own thread.
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeBudgetMillis);

        // Staging and writing come first, and get the budget first. A caller who closed the client and
        // then let the process end has to find those events on the next run, and that is worth more
        // than the post below. A batch on disk is delivered next run, while an event never staged is
        // gone whether the post would have succeeded or not. Where nothing persists there is nothing to
        // write: the final delivery encodes what it sends, and offline nothing is sent.
        Future<?> finalCommit = store.isPersisting() ? commitWithinBudget(deadlineNanos) : null;

        // Then deliver what is buffered, with whatever is left. This waits rather than firing and
        // forgetting because it is this run's last chance to send them. While offline that chance is
        // not taken: offline is the application telling the SDK to stay off the network, and shutting
        // down does not revoke that. The commit above has written those events down where persistence
        // is on, so they go out on a later run; where it is off they are discarded. It is queued on the
        // scheduler like every delivery, so it starts only once a post or retry already running there
        // ends, and waiting for that comes out of the same budget.
        //
        // It posts whatever wait the backoff is in, which shutting down cuts short. After a refusal
        // that wait is minutes to an hour, and where nothing persists the events would not outlive it.
        Callable<DeliveryOutcome> finalDelivery = () -> {
            postingThroughTheBackoff = true;
            try {
                return deliverPayloadAndRetryLater();
            } finally {
                postingThroughTheBackoff = false;
            }
        };
        Future<DeliveryOutcome> delivery = submit(finalDelivery);
        if (delivery != null) {
            try {
                awaitDelivery(delivery, Math.max(0, deadlineNanos - System.nanoTime()));
            } catch (TimeoutException e) {
                // Deliberately not canceled. The batch is closed and on its way, so interrupting now
                // would make the loss certain, while leaving it to run costs nothing: the scheduler
                // thread is a daemon, and returning from close() does not end an Android process.
                logger.warn("Gave up waiting for the final event delivery after {}ms;" +
                        " it continues in the background", closeBudgetMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                logUnexpectedError(e.getCause() == null ? e : e.getCause());
            }
        }
        // The store and the senders, by contrast, are released on whichever thread uses them, after
        // whatever is still in flight there. Closing them inline would pull them out from under a
        // delivery, or a diagnostic post, that we just decided not to wait for. shutdown() then
        // refuses new work while letting what is already queued finish -- unlike shutdownNow(), which
        // would interrupt those and strand the futures of anything it discarded.
        //
        // Queueing and shutting down are one step under submitLock, so that a flush cannot land in
        // between them and put a delivery behind the release, where it would find the store closed.
        synchronized (submitLock) {
            shuttingDown = true;
            if (pendingRetry != null) {
                // Not interrupted if it is already posting: it checks shuttingDown before it starts, and
                // one that got past that runs ahead of the release queued below on the same thread.
                pendingRetry.cancel(false);
                pendingRetry = null;
            }
            // The store is released only once the final commit is through with it, which may be after
            // the budget if the disk stalled. That waits here, on the scheduler thread, and never on the
            // caller's.
            try {
                scheduler.submit(guarded(() -> {
                    awaitQuietly(finalCommit);
                    releaseDeliveryResources();
                }));
            } catch (RuntimeException e) { // the executor was shut down under us
                releaseDeliveryResources();
            }
            queueRelease(diagnosticExecutor, this::releaseDiagnosticResources);
            scheduler.shutdown();
            diagnosticExecutor.shutdown();
            // Commits already queued there still run, and find the processor closed.
            commitExecutor.shutdown();
        }
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
     * @return the commit, for the store's release to wait on, or null if it could not be queued
     */
    private Future<?> commitWithinBudget(long deadlineNanos) {
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
                    " it continues in the background", closeBudgetMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logUnexpectedError(e.getCause() == null ? e : e.getCause());
        }
        return commit;
    }

    /** Waits for a task to finish however it ends. Only for threads that are not the caller's. */
    private static void awaitQuietly(Future<?> task) {
        if (task == null) {
            return;
        }
        try {
            task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Logged where it ran.
        }
    }

    /** Queues a release on the thread that owns those resources, or runs it here if that thread has gone. */
    private void queueRelease(ExecutorService executor, Runnable release) {
        try {
            executor.submit(guarded(release));
        } catch (RuntimeException e) { // the executor was shut down under us
            release.run(); // so nothing can still be using them
        }
    }

    /** What the delivery thread owns: the store it drains and the sender it posts batches through. */
    private void releaseDeliveryResources() {
        store.close();
        closeQuietly(analyticsEventSender);
    }

    /** What the diagnostics thread owns: the sender nothing else posts through. */
    private void releaseDiagnosticResources() {
        closeQuietly(diagnosticEventSender);
    }

    private void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            logUnexpectedError(e);
        }
    }

    /**
     * Serializes and sends everything buffered, for the periodic flush and for what a previous run left,
     * which have nobody waiting to find out how it went. The periodic one is a fixed-delay series, so a run
     * is only ever scheduled once the one before it has finished and these cannot pile up the way requested
     * flushes could.
     * <p>
     * Paced where events give way to flags, since nobody asked for it now: see {@link #awaitTurnToPost()}.
     */
    private void deliverPayload() {
        pacedPass = givesWayToFlags;
        pacedPostsInPass = 0;
        try {
            commitAndDeliverReportingOutcome();
        } finally {
            pacedPass = false;
        }
    }

    /**
     * Writes everything accepted so far, then delivers.
     * <p>
     * This commit looks redundant with the one in {@link #commitAndCloseBatch()}, and online it is: the
     * second finds nothing pending and returns. It is kept for the case where it is the only one. A
     * delivery refused for being offline returns before {@code commitAndCloseBatch}. Without
     * this, a flush while offline would leave everything recorded in memory, and a process that then
     * died would lose it -- the case persistence exists for. The periodic flush needs it most: evaluations
     * short of a full pending run, with no track or identify among them, are otherwise committed by
     * nothing for as long as an outage lasts.
     * <p>
     * Only where events persist. Otherwise the commit makes nothing durable: online, the one in
     * {@code commitAndCloseBatch} is the drain, and offline it would encode events that cannot be sent
     * into bytes that wait in memory exactly as the events would have.
     */
    private DeliveryOutcome commitAndDeliverReportingOutcome() {
        if (store.isPersisting()) {
            commitDurably();
        }
        return deliverPayloadAndRetryLater();
    }

    /**
     * How a delivery went, or the retry that will say, for a caller waiting to find out.
     */
    private static final class DeliveryOutcome {
        /** For a delivery that closed no batch, so there are no losses it can speak for. */
        static final DeliveryOutcome CLOSED_NOTHING = new DeliveryOutcome(false, null, 0);

        final boolean delivered;
        /**
         * Non-null when a batch failed and the retry of every batch is queued to run after the first wait of
         * the backoff, which is as long as a caller waits to hear how it went.
         */
        final Future<Boolean> retry;
        /**
         * The loss count as of the close of this delivery's batch, which is as far as it can answer
         * for; see {@link #claimEventsLost(long)}.
         */
        final long lostEventsMark;

        private DeliveryOutcome(boolean delivered, Future<Boolean> retry, long lostEventsMark) {
            this.delivered = delivered;
            this.retry = retry;
            this.lostEventsMark = lostEventsMark;
        }
    }

    /**
     * Waits for a delivery and, when it queued a retry, for that too, within one budget.
     *
     * @param timeoutNanos how long to wait in all
     */
    private void awaitDelivery(Future<DeliveryOutcome> delivery, long timeoutNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + timeoutNanos;
        DeliveryOutcome outcome = delivery.get(timeoutNanos, TimeUnit.NANOSECONDS);
        if (outcome != null && outcome.retry != null) {
            outcome.retry.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Delivers every batch waiting and then what has been committed since, queuing one retry for when the
     * backoff allows if a batch is still waiting.
     * <p>
     * The retry is handed back for the caller to wait on only where it comes after the first wait. Any
     * longer one, a refusal's or one grown through an outage, is the backoff saying not to post for a
     * while, and a flush that sat it out would hold its caller for minutes to say what it can say now.
     * <p>
     * Runs on the scheduler thread, as every delivery does, so no two overlap; see the class
     * documentation.
     */
    private DeliveryOutcome deliverPayloadAndRetryLater() {
        if (offline.get()) {
            return DeliveryOutcome.CLOSED_NOTHING;
        }

        DeliveryPass pass = deliverWaitingThenNew();
        if (pass.outcome.kept()) {
            // A batch kept for a reason other than the network, such as a read that failed, has no wait of its
            // own, and is retried after the first one.
            long waitNanos = retryState.remainingWaitNanos();
            long delayNanos = waitNanos > 0 ? waitNanos
                    : TimeUnit.MILLISECONDS.toNanos(retryState.initialDelayMillis());
            Future<Boolean> retry = scheduleRetry(delayNanos);
            boolean awaited = delayNanos <= TimeUnit.MILLISECONDS.toNanos(retryState.initialDelayMillis());
            return new DeliveryOutcome(false, awaited ? retry : null, pass.lostEventsMark);
        }
        return new DeliveryOutcome(pass.outcome == BatchesOutcome.DELIVERED, null, pass.lostEventsMark);
    }

    /** How one pass over the batches went, and the loss count it can answer for. */
    private static final class DeliveryPass {
        final BatchesOutcome outcome;
        /** As {@link DeliveryOutcome#lostEventsMark}. */
        final long lostEventsMark;

        DeliveryPass(BatchesOutcome outcome, long lostEventsMark) {
            this.outcome = outcome;
            this.lostEventsMark = lostEventsMark;
        }
    }

    /**
     * Delivers the batches already waiting and, only once none of them is left blocked, closes what has been
     * committed into a batch of its own and delivers that.
     * <p>
     * Closing first would add a batch for every pass a failure lasts: every flush and every periodic
     * delivery of an outage would leave one more file, or one more batch held in memory, each with the
     * summary its commit wrote. Held back, what is committed meanwhile goes on into the open log, which is
     * closed once it is full or once the batches ahead of it are gone, so an outage costs a batch per
     * {@link EventLogFormat#MAX_BATCH_SIZE} rather than one per pass. It is committed all the same, since
     * a pass is a commit point.
     */
    private DeliveryPass deliverWaitingThenNew() {
        BatchesOutcome waiting = deliverPendingBatches();
        if (waiting.kept()) {
            return new DeliveryPass(waiting, commitLeavingTheLogOpen());
        }
        Close close = commitAndCloseBatch();
        BatchesOutcome closed = deliverListedBatches();
        if (closed.kept() || !close.closedEverything) {
            // Events left in a log that would not close are still on the disk, for the next close to try again
            // with; this pass cannot say they got out.
            return new DeliveryPass(closed.kept() ? closed : BatchesOutcome.RETRYABLE, close.lostEventsMark);
        }
        boolean delivered = waiting == BatchesOutcome.DELIVERED && closed == BatchesOutcome.DELIVERED;
        return new DeliveryPass(delivered ? BatchesOutcome.DELIVERED : BatchesOutcome.FAILED, close.lostEventsMark);
    }

    /** What {@link #commitAndCloseBatch()} did. */
    private static final class Close {
        /** As {@link DeliveryOutcome#lostEventsMark}. */
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
     * A flush, requested or periodic, has usually just committed in {@link #commitAndDeliverReportingOutcome()},
     * so this commit often finds nothing to do. It cannot be the one left out: that earlier commit is only
     * where events persist, and the retry and the final delivery at close reach here without it.
     *
     * @return the loss count as of the close, for {@link #claimEventsLost(long)}, and whether the
     *   close took every committed event into a batch; both under the lock, so they cover this close and
     *   nothing a later commit does
     */
    private Close commitAndCloseBatch() {
        synchronized (commitLock) {
            commitDurably();
            store.closeBatch();
            return new Close(lostEvents.get(), !store.holdsUnclosedEvents());
        }
    }

    /**
     * {@link #commitAndCloseBatch()} for a pass that found an earlier batch blocked, and so closes nothing.
     * The mark is as of this commit, the last point anything the caller recorded was staged.
     *
     * @return the loss count as of the commit, for {@link #claimEventsLost(long)}
     */
    private long commitLeavingTheLogOpen() {
        synchronized (commitLock) {
            commitDurably();
            return lostEvents.get();
        }
    }

    private enum BatchesOutcome {
        DELIVERED,
        FAILED,
        /** Kept after an attempt that failed in a way that may pass, or a read or listing that did. */
        RETRYABLE,
        /** Kept without an attempt, the backoff not allowing one yet. */
        HELD;

        boolean kept() {
            return this == RETRYABLE || this == HELD;
        }
    }

    /**
     * Makes one attempt at every batch waiting, oldest first: the others are deliveries an earlier attempt
     * did not finish, or that a previous run of the application never got to start.
     * <p>
     * Stops at the first failure that may pass. The batches behind it would most likely meet the same
     * network, and the retry takes all of them again.
     */
    private BatchesOutcome deliverPendingBatches() {
        reportDiscarded(store.discardExpired());
        return deliverListedBatches();
    }

    /**
     * {@link #deliverPendingBatches()} without the discard, for a second pass moments after the first.
     * <p>
     * Where the directory could not be listed, delivers the batches the store last counted and answers
     * {@link BatchesOutcome#RETRYABLE}: a batch the listing could not see is still on the disk, and the
     * listing may work next time.
     */
    private BatchesOutcome deliverListedBatches() {
        EventStore.Listing listing = store.listPendingBatches();
        boolean allDelivered = true;
        for (EventStore.Batch batch : listing.batches) {
            if (pacedPass) {
                awaitTurnToPost();
            }
            if (offline.get()) {
                return BatchesOutcome.FAILED;
            }
            BatchesOutcome outcome = deliver(batch);
            if (pacedPass) {
                pacedPostsInPass++;
                lastPacedPostEndedNanos = System.nanoTime();
            }
            if (outcome.kept()) {
                return outcome;
            }
            allDelivered &= outcome == BatchesOutcome.DELIVERED;
        }
        if (!listing.complete) {
            return BatchesOutcome.RETRYABLE;
        }
        return allDelivered ? BatchesOutcome.DELIVERED : BatchesOutcome.FAILED;
    }

    /**
     * Queues the retry on the scheduler rather than sleeping for it there, which would hold every flush,
     * and close()'s final delivery, behind the wait.
     *
     * @param delayNanos how long until the backoff allows the next post
     * @return the retry, a retry already queued, or null if the processor is shutting down
     */
    private Future<Boolean> scheduleRetry(long delayNanos) {
        synchronized (submitLock) {
            if (shuttingDown) {
                return null;
            }
            if (pendingRetry != null && !pendingRetry.isDone()) {
                return pendingRetry;
            }
            try {
                pendingRetry = scheduler.schedule(this::retryPendingBatches, delayNanos, TimeUnit.NANOSECONDS);
            } catch (RuntimeException e) { // the executor was shut down under us
                return null;
            }
            logger.warn("Will retry posting events in {}ms after a failure",
                    TimeUnit.NANOSECONDS.toMillis(delayNanos));
            return pendingRetry;
        }
    }

    /**
     * The one retry. A failure here queues no other; the batches wait on disk, or in memory, for the next
     * flush, which posts once the backoff allows. Until it does, the periodic one each time queues this
     * retry for when it will.
     * <p>
     * Stopped by the backoff instead, it queues itself again for when the wait is up. That wait is one a
     * post of its own began, such as the second refusal that dropped a batch ahead of the ones it stopped at,
     * or one a post in the meantime made longer; either way nothing has been tried, and the flushes that
     * took this retry as theirs, while it was queued, queued no other.
     * <p>
     * A pass like the delivery's rather than only the batches it left: the delivery may have closed
     * nothing, behind a batch that was blocked, and a {@code true} has to mean that the events the
     * caller was waiting on went too.
     */
    private boolean retryPendingBatches() {
        synchronized (submitLock) {
            if (shuttingDown) {
                // close() has queued the release of the store and the sender behind this.
                return false;
            }
        }
        if (offline.get()) {
            return false;
        }
        try {
            BatchesOutcome outcome = deliverWaitingThenNew().outcome;
            if (outcome == BatchesOutcome.HELD) {
                synchronized (submitLock) {
                    // This one, which is running, so that it does not stand in for the next.
                    pendingRetry = null;
                }
                scheduleRetry(retryState.remainingWaitNanos());
            }
            return outcome == BatchesOutcome.DELIVERED;
        } catch (RuntimeException e) {
            logUnexpectedError(e);
            return false;
        }
    }

    /**
     * @return DELIVERED if the batch arrived, or held nothing left to send; FAILED if events were lost,
     *   whether the batch could not be read, arrived short, or had its payload refused
     *   {@link #BAD_REQUESTS_BEFORE_DROPPING} times in a row; RETRYABLE if it is kept to be sent again,
     *   which is every other failure to post it; HELD for a post the backoff did not allow
     */
    private BatchesOutcome deliver(EventStore.Batch batch) {
        if (!postingThroughTheBackoff && retryState.remainingWaitNanos() > 0) {
            // Not an attempt, so it moves nothing in the backoff.
            return BatchesOutcome.HELD;
        }
        EventLogFormat.Payload payload;
        try {
            payload = store.payload(batch);
        } catch (EventStore.UnreadableBatchException e) {
            // Lost: nothing will ever send it, so it is removed, the rest are still attempted, and the
            // caller hears that this pass lost events.
            logger.error("Dropping {} stored event(s) this version cannot read", batch.eventCount);
            store.remove(batch);
            reportBatchLost(batch);
            return BatchesOutcome.FAILED;
        } catch (IOException e) {
            // Kept: the file is still there, and a read that failed may not fail next time.
            logger.warn("Could not read stored events: {}", LogValues.exceptionSummary(e));
            return BatchesOutcome.RETRYABLE;
        }
        if (payload == null) {
            // Already delivered by another process of this application, or holding no events. Either way
            // nothing is lost and there is nothing to keep.
            store.remove(batch);
            return BatchesOutcome.DELIVERED;
        }
        // Damage after the first frame: what came before it is sent, and the rest is gone with the file. Counted
        // once the batch is removed rather than now, since one kept for a retry is read, and found short, again.
        int leftOut = Math.max(0, batch.eventCount - payload.eventCount);
        if (diagnosticStore != null) {
            diagnosticStore.recordEventsInBatch(payload.eventCount);
        }
        store.startedSending(batch);
        try {
            AnalyticsEventSender.Result result = analyticsEventSender.sendBatch(payload, payload.eventCount,
                    batch.payloadId, eventsUri);
            if (!result.success) {
                retryState.recordFailure(result.unexpected, result.retryAfterMillis);
                if (countBadRequest(batch, result.status) >= BAD_REQUESTS_BEFORE_DROPPING) {
                    // The rest are still attempted, once the backoff allows.
                    logger.error("Dropping {} event(s) whose payload the service refused {} times in a row, "
                            + "last with HTTP {}", batch.eventCount, BAD_REQUESTS_BEFORE_DROPPING, result.status);
                    store.remove(batch);
                    reportBatchLost(batch);
                    return BatchesOutcome.FAILED;
                }
                // Kept for any other failure, a 401 included: a valid key is sometimes refused for minutes, and
                // deleting on a refusal would lose every event recorded meanwhile. The backoff decides when the
                // retry, the next flush or the next run of the application sends it again.
                return BatchesOutcome.RETRYABLE;
            }
            countBadRequest(batch, result.status);
            retryState.recordSuccess();
            if (result.serverTime != null) {
                recordPastTime(result.serverTime.getTime());
            }
            store.remove(batch);
            if (leftOut > 0) {
                logger.error("Dropped {} damaged stored event(s) from a batch it delivered", leftOut);
                lostEvents.addAndGet(leftOut);
                return BatchesOutcome.FAILED;
            }
            return BatchesOutcome.DELIVERED;
        } catch (Exception e) {
            logUnexpectedError(e);
            return BatchesOutcome.FAILED;
        } finally {
            // Counted again unless the batch was removed above.
            store.stoppedSending(batch);
        }
    }

    /**
     * @return how many refusals of the payload in a row this batch has had, counting this response; zero
     *   for any other status, which ends the run, and once the batch is dropped
     */
    private int countBadRequest(EventStore.Batch batch, int status) {
        synchronized (badRequestLock) {
            if (!LDUtil.isEventPayloadRefused(status)) {
                badRequestPayloadId = null;
                badRequestsInARow = 0;
                return 0;
            }
            if (!batch.payloadId.equals(badRequestPayloadId)) {
                badRequestPayloadId = batch.payloadId;
                badRequestsInARow = 0;
            }
            badRequestsInARow++;
            int count = badRequestsInARow;
            if (count >= BAD_REQUESTS_BEFORE_DROPPING) {
                badRequestPayloadId = null;
                badRequestsInARow = 0;
            }
            return count;
        }
    }

    /**
     * Posts an event that has already been built. Requires the caller to have checked
     * {@link #diagnosticsSuspended()}, because for the periodic event building it is what consumes
     * the period.
     */
    private void sendDiagnosticEvent(DiagnosticEvent diagnosticEvent, boolean isInit) {
        try {
            byte[] data = diagnosticEvent.getJsonValue().toJsonString()
                    .getBytes(StandardCharsets.UTF_8);
            handleResponse(diagnosticEventSender.sendDiagnosticEvent(data, eventsUri));
            if (isInit) {
                // Attempted, not delivered. A failed post gives back an unsuccessful Result rather
                // than throwing, so this marks the init event done either way and the process never
                // retries it. That matches DefaultEventProcessor, which is the behavior to keep:
                // diagnostics are best-effort telemetry about the SDK, and a retry that outlived
                // its own init would describe a configuration the application has moved on from.
                diagnosticInitSent = true;
            }
        } catch (Exception e) {
            logUnexpectedError(e);
        }
    }

    private void sendDiagnosticStats() {
        if (diagnosticsSuspended() || diagnosticStore == null || !claimDiagnosticPost()) {
            return;
        }
        DiagnosticStore store = diagnosticStore;
        postDiagnostic(() -> {
            // The check that decides the outcome, and it has to come before the event is built rather
            // than after. createEventAndReset hands back the period and clears it: the stream init
            // records, the events-in-batch count and the period start all move. getAndClearDroppedCount
            // does the same for the dropped count. This runs on the diagnostics thread, which may have
            // been busy with an earlier post for as long as that post took, so the state can easily
            // have changed since the checks above. Bailing out after the event was built would discard
            // a period outright and leave the next event describing a window that begins after the
            // reset. Bailing out here leaves everything where it is, for the next period to carry.
            if (diagnosticsSuspended()) {
                return;
            }
            sendDiagnosticEvent(store.createEventAndReset(getAndClearDroppedCount(), 0), false);
        });
    }

    /**
     * Takes the diagnostic posting thread, or reports that the last event is still on it.
     * <p>
     * A new event is dropped rather than queued behind the old one. Diagnostics are best-effort
     * telemetry, and a post can take tens of seconds against a network that never answers. Queuing
     * would let an outage accumulate events describing an SDK state the application has since moved
     * past. The same reasoning makes a failed init event final rather than retried.
     */
    private boolean claimDiagnosticPost() {
        if (diagnosticPostInFlight.compareAndSet(false, true)) {
            return true;
        }
        logger.debug("Skipped a diagnostic event because the previous one is still being posted");
        return false;
    }

    /**
     * Hands a claimed post to the diagnostics thread, releasing the claim once it ends.
     *
     * @param post what to run there, which must hold a claim from {@link #claimDiagnosticPost()}
     */
    private void postDiagnostic(Runnable post) {
        Runnable releasing = () -> {
            try {
                post.run();
            } finally {
                diagnosticPostInFlight.set(false);
            }
        };
        try {
            diagnosticExecutor.submit(guarded(releasing));
        } catch (RuntimeException e) { // the executor was shut down under us
            diagnosticPostInFlight.set(false);
        }
    }

    /**
     * Unlike analytics events, diagnostics are not sent while offline or in the background.
     * <p>
     * {@link #updateScheduledTasks} cancels the periodic task when either becomes true, but that is
     * not enough on its own. Canceling does not stop a run already underway, and the init event is
     * submitted before it reaches the executor. Either can arrive here after the state changed.
     */
    private boolean diagnosticsSuspended() {
        return isStopped() || offline.get() || inBackground.get();
    }

    /**
     * A diagnostic response is only read for the service's clock. One the service refused stops nothing:
     * analytics delivery backs off on its own responses, and a diagnostic is not retried anyway.
     */
    private void handleResponse(EventSender.Result result) {
        if (result != null && result.getTimeFromServer() != null) {
            recordPastTime(result.getTimeFromServer().getTime());
        }
    }

    /**
     * Moves the threshold forwards only. Analytics and diagnostic responses are handled on separate
     * threads, so a plain assignment would let an older reading of the service clock overwrite a
     * newer one and keep debug events alive past the date the service set. A loop rather than
     * {@code accumulateAndGet}, which needs API 24.
     */
    private void recordPastTime(long timeFromServer) {
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

    /**
     * Must be called holding {@code stateLock}. Once closed, this only ever cancels. close() sets the
     * flag and then calls this under the same lock, so a call that got here first has its tasks
     * canceled by close(), and any call after it finds the flag set.
     */
    private void updateScheduledTasks(boolean inBackground, boolean offline) {
        boolean stopped = closed.get();
        // Flushing stays scheduled even while we are offline or in the background; a run while
        // offline commits, where events persist, and sends nothing. Canceling it for an outage would restart the
        // interval on every reconnect, and a run of brief outages would then hold events back for
        // far longer than one interval. Left running, what an outage buffered goes out at the first
        // run after it ends.
        flushTask = enableOrDisableTask(!stopped, flushTask, flushIntervalMillis,
                this::deliverPayload);
        if (!stopped && !offline && !wentOnline) {
            wentOnlineNanos = System.nanoTime();
            wentOnline = true;
            if (!launchGateOpen) {
                scheduleLaunchGateCheck(TimeUnit.MILLISECONDS.toNanos(flagTimings.launchFloorMillis));
            }
        }
        // Events a previous run left on disk have already waited out a whole process, so they do not
        // wait out an interval as well, only the launch gate. An application that dies within one interval
        // of every start would otherwise never deliver them.
        if (!stopped && !offline && launchGateOpen && hasEventsFromPreviousRun.compareAndSet(true, false)) {
            submit(this::deliverPayload);
        }
        boolean diagnosticsEnabled = !stopped && diagnosticStore != null && !offline && !inBackground;
        diagnosticTask = enableOrDisableTask(diagnosticsEnabled, diagnosticTask,
                diagnosticRecordingIntervalMillis, this::sendDiagnosticStats);
        if (diagnosticsEnabled && launchGateOpen && !diagnosticInitSent && claimDiagnosticPost()) {
            DiagnosticStore store = diagnosticStore;
            postDiagnostic(() -> {
                // Re-checked on the posting thread: going online and coming to the foreground are
                // two separate calls, and both want to send the init event we never got to send.
                // Suspension is re-checked for the same reason it is for the periodic event, and
                // costs nothing here: getInitEvent consumes nothing, so a skipped init is simply
                // built again the next time diagnostics are enabled.
                if (!diagnosticInitSent && !diagnosticsSuspended()) {
                    sendDiagnosticEvent(store.getInitEvent(), true);
                }
            });
        }
    }

    /**
     * Opens the launch gate if it may open now, and otherwise looks again when it next could: at the floor,
     * then at the cap, or sooner once the flag request settles. On the scheduler thread.
     */
    private void checkLaunchGate() {
        if (launchGateOpen || isStopped()) {
            return;
        }
        long sinceOnline = System.nanoTime() - wentOnlineNanos;
        long floor = TimeUnit.MILLISECONDS.toNanos(flagTimings.launchFloorMillis);
        long cap = TimeUnit.MILLISECONDS.toNanos(flagTimings.launchCapMillis);
        if (sinceOnline < floor) {
            scheduleLaunchGateCheck(floor - sinceOnline);
            return;
        }
        String why = flagTraffic.launchGateReason(sinceOnline >= cap, flagTimings.launchCapMillis);
        if (why == null) {
            scheduleLaunchGateCheck(cap - sinceOnline);
            return;
        }
        launchGateOpen = true;
        if (hasEventsFromPreviousRun.get()) {
            logger.debug("Sending events from a previous run after {}", why);
        }
        synchronized (stateLock) {
            updateScheduledTasks(inBackground.get(), offline.get());
        }
    }

    private void scheduleLaunchGateCheck(long delayNanos) {
        synchronized (submitLock) {
            if (shuttingDown) {
                return;
            }
            try {
                scheduler.schedule(guarded(this::checkLaunchGate), delayNanos, TimeUnit.NANOSECONDS);
            } catch (RuntimeException e) { // the executor was shut down under us
            }
        }
    }

    /**
     * Holds the next batch of a delivery this processor started on its own: until a gap has passed since the
     * last one answered, and then while a flag request is in flight, up to a limit. A backlog this sends
     * unasked otherwise takes the network for as long as it lasts, an {@code identify}'s flag request
     * included.
     * <p>
     * A delivery someone asked for, or close, ends the pacing, and the rest go out back to back: the delivery
     * either queues runs on this thread, behind this one. Going offline ends the wait too, but the pass then
     * stops before its next post, leaving the rest for a later delivery.
     */
    private void awaitTurnToPost() {
        long gapEnds = pacedPostsInPass == 0 ? System.nanoTime()
                : lastPacedPostEndedNanos + TimeUnit.MILLISECONDS.toNanos(flagTimings.gapMillis);
        try {
            if (!flagTraffic.awaitTurn(gapEnds, TimeUnit.MILLISECONDS.toNanos(flagTimings.flagRequestWaitMillis),
                    () -> closed.get() || offline.get() || requestedDeliveryQueued)) {
                pacedPass = false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pacedPass = false;
        }
    }

    private ScheduledFuture<?> enableOrDisableTask(
            boolean shouldEnable,
            ScheduledFuture<?> currentTask,
            long intervalMillis,
            Runnable task
    ) {
        if (!shouldEnable) {
            if (currentTask != null) {
                currentTask.cancel(false);
            }
            return null;
        }
        if (currentTask != null && !currentTask.isDone()) {
            return currentTask;
        }
        if (currentTask != null) {
            // Backstop for a throwable that escaped guarded() anyway, such as one thrown while
            // logging the first. The executor marks the repeating future done and never fires it
            // again, and holding that future here would make every later enable a no-op.
            currentTask.cancel(false);
        }
        try {
            // Fixed delay rather than fixed rate: a cached process stops running its tasks without
            // stopping the clock, so at a fixed rate it would come back owing every run it missed and
            // fire them one after another.
            return scheduler.scheduleWithFixedDelay(guarded(task), intervalMillis, intervalMillis,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) { // the executor was shut down under us
            return null;
        }
    }

    /**
     * Puts work on the delivery thread, and is the one place that decides whether there is still a
     * thread willing to take it. The {@code isStopped} tests the callers make first are a fast path
     * that saves the work of getting here, not a guarantee; this is what a delivery is actually
     * ordered against close().
     *
     * @return the submitted task, or null if the processor is shutting down or already has
     */
    private Future<?> submit(Runnable task) {
        synchronized (submitLock) {
            if (shuttingDown) {
                // close() has already queued the release of the sender. Work accepted now would be
                // behind it in the queue and would run against a sender that had been closed.
                return null;
            }
            try {
                return scheduler.submit(guarded(task));
            } catch (RuntimeException e) { // the executor was shut down under us
                return null;
            }
        }
    }

    /**
     * As {@link #submit(Runnable)}, for the final delivery at close(), whose outcome close() waits
     * for. Not wrapped in {@link #guarded}, because here the caller is there to receive what
     * escapes.
     *
     * @return the submitted task, or null if the processor is shutting down or already has
     */
    private <T> Future<T> submit(Callable<T> task) {
        synchronized (submitLock) {
            if (shuttingDown) {
                return null;
            }
            try {
                return scheduler.submit(task);
            } catch (RuntimeException e) { // the executor was shut down under us
                return null;
            }
        }
    }

    /**
     * Keeps an unexpected failure from killing a repeating task or bubbling out of the executor.
     * Anything that escapes a run suppresses the rest of a {@code scheduleWithFixedDelay} series,
     * so this catches {@code Throwable} and not just {@code Exception}. Otherwise a
     * {@code StackOverflowError} from nested {@code LDValue} data, or an {@code OutOfMemoryError}
     * growing the payload stream, would stop flushing for the life of the process with nothing
     * logged.
     */
    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                logUnexpectedError(t);
            }
        };
    }

    private boolean isStopped() {
        return closed.get();
    }

    private void logUnexpectedError(Throwable e) {
        logger.error("Unexpected error in event processor: {}", LogValues.exceptionSummary(e));
        logger.debug("{}", LogValues.exceptionTrace(e));
    }
}
