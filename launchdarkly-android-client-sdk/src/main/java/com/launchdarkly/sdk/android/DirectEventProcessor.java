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
 * event is kept as an {@link Event} until a commit turns the run of them into bytes and writes them, so
 * evaluations of untracked flags cost a counter increment and evaluations of tracked ones cost that plus a
 * list append. Nothing is encoded on the thread that evaluated.
 * <p>
 * Where the line falls is the subject of O12 in the event-encoding research, and it falls between the events
 * an application asked for by name and the ones it did not. {@code track} and {@code identify} are commit
 * points, because an application reporting an error is saying this matters more than the microseconds it
 * costs and the crash it describes may be moments away; a commit encodes the whole held run, so the
 * exposures leading up to the error go down with it. Evaluations get no such promise and are committed once
 * {@link #PENDING_COMMIT_THRESHOLD} of them have accumulated, on {@code flush}, or when the application
 * flushes from its own crash handler.
 * <p>
 * Whether a commit point runs on the caller's thread is the application's choice, through
 * {@link com.launchdarkly.sdk.android.integrations.EventProcessorBuilder#eventPersistence(
 * com.launchdarkly.sdk.android.integrations.EventPersistence)}. Only at
 * {@code IMMEDIATE} can {@code track} promise the event is on disk by the time it returns.
 */
final class DirectEventProcessor implements EventProcessor {
    /**
     * How many full events may be held unencoded before one of them pays for a commit.
     * <p>
     * It replaces the store's staged-byte threshold, which cannot apply here because there are no bytes
     * until the commit runs, and it sets two things at once.
     * <p>
     * The first is how many evaluations a termination can take. That is narrower than everything a crash
     * could take: an uncaught exception runs the application's handler while the process is still alive, and
     * a handler that flushes commits the whole held run, so this is the window for the terminations that run
     * nothing on the way out -- {@code SIGKILL}, a native crash, an ANR kill, and the system reclaiming a
     * backgrounded process. A {@code track} closes it too, because it commits.
     * <p>
     * The second is the worst a {@code track} can cost, since it encodes whatever is held before returning.
     * The common case is far below the bound, because the commit executor keeps the run drained; raising
     * this trades that tail against the number of writes.
     */
    private static final int PENDING_COMMIT_THRESHOLD = 32;

    /**
     * How long {@link #close()} spends waiting for the final delivery before it gives up and returns.
     * <p>
     * {@code close()} runs on the caller's thread, which for an application shutting down is usually the
     * main one, so this has to stay well inside the five seconds Android allows before an unanswered
     * input event becomes an ANR. Two seconds is where the return on waiting longer falls off: the
     * events client keeps pooled connections for only five seconds against a thirty second flush
     * interval, so this post nearly always pays a full DNS, TCP and TLS handshake -- about three round
     * trips, which two seconds covers up to roughly a 600ms RTT. A network slower than that is one the
     * post is likely to fail on anyway.
     * <p>
     * Overshooting the budget is cheaper than it looks, because the delivery is not cancelled when the
     * budget expires; see {@link #close()}.
     */
    static final long DEFAULT_CLOSE_BUDGET_MILLIS = 2_000;

    private final OutboundEventBuffer eventBuffer;
    private final EventStore store;
    private final EventSender diagnosticEventSender;
    private final AnalyticsEventSender analyticsEventSender;
    private final URI eventsUri;
    private final DiagnosticStore diagnosticStore;
    private final long flushIntervalMillis;
    private final long diagnosticRecordingIntervalMillis;
    private final long closeBudgetMillis;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService diagnosticExecutor;
    private final LDLogger logger;

    private final AtomicBoolean inBackground;
    private final AtomicBoolean offline;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    // Set when the service tells us to stop, e.g. because the mobile key is invalid.
    private volatile boolean disabled = false;
    private volatile boolean diagnosticInitSent = false;
    private final AtomicLong lastKnownPastTime = new AtomicLong(0);
    /**
     * Whether a previous run of the application left events behind.
     * <p>
     * Those events are already as old as whatever happened to that run, so they are sent as soon as the
     * SDK is allowed to send anything rather than at the next interval. The application in front of the
     * user has moved on; an error report about a crash is not worth much thirty seconds late, and the
     * process might not live that long either.
     */
    private final AtomicBoolean hasEventsFromPreviousRun = new AtomicBoolean(false);

    private final Object stateLock = new Object();
    private ScheduledFuture<?> flushTask;
    private ScheduledFuture<?> diagnosticTask;

    /**
     * Full events recorded but not yet encoded, guarded by {@link #recordLock}.
     * <p>
     * Held rather than serialized because serializing early would not make them durable: the store stages
     * bytes into memory too, and only {@link EventStore#commit()} reaches the file. Both forms are equally
     * lost to a crash, so the encode may as well happen where it is cheapest.
     */
    private final List<Event> pending = new ArrayList<>();

    /**
     * How many events a commit has taken from {@link #pending} and not yet staged, guarded by
     * {@link #recordLock}. The encode between the two happens outside that lock, and capacity has to
     * count these events for as long as they are in neither place.
     */
    private int eventsBeingStaged;

    /**
     * Guards everything one recording writes: {@link #pending} and the summary counters behind
     * {@link #buffer}.
     * <p>
     * One evaluation can produce a counter, a full event and a debug event, and the three have to
     * land together. Taken separately, a flush landing between them splits one evaluation across two
     * payloads, and a {@link #close()} landing between them delivers the counter and then refuses the
     * full event -- leaving a summary that says an evaluation happened and no event to go with it.
     * <p>
     * Held for the appends and the handover of the run, never across the encode. Recording runs on
     * whichever thread evaluated a flag, which on Android is usually the main one. The worst it may
     * wait for is another thread's memory operation; if the encoder ran under this lock, an
     * evaluation would instead wait on the dominant cost of the whole path.
     */
    private final Object recordLock = new Object();

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
     * Whether a commit is already queued, so a run of recordings past the threshold submits one task rather
     * than one per event.
     */
    private final AtomicBoolean commitScheduled = new AtomicBoolean(false);

    /**
     * How many events the SDK will hold in total, across {@link #pending} and the store.
     */
    private final int capacity;

    private final AtomicBoolean capacityExceeded = new AtomicBoolean(false);

    /** Whether the summarizer being full has already been reported, so it is logged once per run. */
    private final AtomicBoolean summaryContextsExceeded = new AtomicBoolean(false);

    private final AtomicLong droppedEvents = new AtomicLong(0);

    /**
     * Whether a commit point encodes and writes before returning, rather than queueing that work.
     */
    private final boolean commitOnCallerThread;

    /**
     * True while a diagnostic event is on its way to the service, so that a later one is dropped
     * rather than queued behind it.
     */
    private final AtomicBoolean diagnosticPostInFlight = new AtomicBoolean(false);

    /**
     * Guards the handover from running to shut down: held for the length of a submit, and by
     * {@link #close()} while it queues the release of each thread's resources and stops the
     * executors accepting work. Nothing blocking happens under it.
     */
    private final Object submitLock = new Object();

    /** Set under {@link #submitLock} once close() has queued the release of those resources. */
    private boolean shuttingDown = false;

    DirectEventProcessor(
            OutboundEventBuffer eventBuffer,
            EventStore store,
            EventSender diagnosticEventSender,
            AnalyticsEventSender analyticsEventSender,
            URI eventsUri,
            DiagnosticStore diagnosticStore,
            int capacity,
            boolean commitOnCallerThread,
            long flushIntervalMillis,
            long diagnosticRecordingIntervalMillis,
            long closeBudgetMillis,
            boolean initiallyInBackground,
            boolean initiallyOffline,
            ScheduledExecutorService scheduler,
            ExecutorService diagnosticExecutor,
            LDLogger logger
    ) {
        this.eventBuffer = eventBuffer;
        this.store = store;
        this.commitOnCallerThread = commitOnCallerThread;
        this.diagnosticEventSender = diagnosticEventSender;
        this.analyticsEventSender = analyticsEventSender;
        this.eventsUri = eventsUri;
        this.diagnosticStore = diagnosticStore;
        this.capacity = capacity;
        this.flushIntervalMillis = flushIntervalMillis;
        this.diagnosticRecordingIntervalMillis = diagnosticRecordingIntervalMillis;
        this.closeBudgetMillis = closeBudgetMillis;
        this.scheduler = scheduler;
        this.diagnosticExecutor = diagnosticExecutor;
        this.logger = logger;
        this.inBackground = new AtomicBoolean(initiallyInBackground);
        this.offline = new AtomicBoolean(initiallyOffline);

        // Off the calling thread: this is on the path of LDClient.init, and it reads the filesystem.
        submit(() -> {
            store.recoverInterruptedLog();
            if (store.pendingBatches().isEmpty()) {
                return;
            }
            // Either end of the race is handled: if the SDK is already allowed to send, these go now,
            // and if it is not, going online later will pick them up.
            if (offline.get() || isStopped()) {
                hasEventsFromPreviousRun.set(true);
            } else {
                deliverPayload();
            }
        });

        synchronized (stateLock) {
            updateScheduledTasks(initiallyInBackground, initiallyOffline);
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
        if (isStopped() || context == null) {
            return;
        }
        Event.FeatureRequest event = new Event.FeatureRequest(System.currentTimeMillis(), flagKey,
                context, flagVersion, variation, value, defaultValue, reason, null,
                requireFullEvent, debugEventsUntilDate, false);
        // Built before the lock is taken, so that the critical section is only the writes.
        Event debugEvent = shouldDebugEvent(debugEventsUntilDate) ? event.toDebugEvent() : null;
        boolean contextsExceeded;
        boolean needsCommit;
        synchronized (recordLock) {
            if (closed.get()) {
                return;
            }
            contextsExceeded = !eventBuffer.summarize(event);
            if (requireFullEvent) {
                addPending(event);
            }
            if (debugEvent != null) {
                addPending(debugEvent);
            }
            needsCommit = pending.size() >= PENDING_COMMIT_THRESHOLD;
        }
        if (contextsExceeded) {
            reportContextsExceeded();
        }
        // Deliberately no commit point. An evaluation is expected to cost what a map lookup costs, and it
        // is usually the main thread doing it; the store writes these on its own thread once enough of them
        // have piled up, and the next event recorded at a commit point makes them durable along with itself.
        if (needsCommit) {
            scheduleCommit();
        }
    }

    @Override
    public void recordIdentifyEvent(LDContext context) {
        if (isStopped() || context == null) {
            return;
        }
        record(new Event.Identify(System.currentTimeMillis(), context));
        commitAtCommitPoint();
    }

    @Override
    public void recordCustomEvent(LDContext context, String eventKey, LDValue data, Double metricValue) {
        if (isStopped() || context == null) {
            return;
        }
        record(new Event.Custom(System.currentTimeMillis(), eventKey, context, data, metricValue));
        commitAtCommitPoint();
    }

    /**
     * Holds an event for the next commit to encode, counting it as dropped if the SDK is already full.
     * <p>
     * Capacity is consulted before anything is encoded, so an event that will not be kept is never encoded.
     * That ordering is what bounds an application re-evaluating a tracked flag in a render loop: once the
     * limit is reached the cost of an evaluation falls back to its summary counter, however fast the loop runs.
     */
    void record(Event event) {
        boolean needsCommit;
        synchronized (recordLock) {
            // The close check that decides the outcome, as against the fast path the public record methods
            // take before building the event. The commit lifts the run out under this same lock, so testing
            // the flag here orders a record against close()'s final commit: either the event is in the list
            // before that commit takes it, or it is refused. Tested outside the lock the two interleave, and
            // an event can be left in a list that nothing will drain again.
            if (closed.get()) {
                return;
            }
            addPending(event);
            needsCommit = pending.size() >= PENDING_COMMIT_THRESHOLD;
        }
        if (needsCommit) {
            scheduleCommit();
        }
    }

    /**
     * Commits at a commit point, on the caller's thread or off it as the application asked.
     * <p>
     * Committing on the caller's thread is what lets {@code track} promise its event is on disk by the time
     * it returns. Scheduling it instead keeps the encode and the write off that thread, and the event is
     * durable a moment later rather than immediately -- which is nothing at all when persistence is off,
     * since there is no disk for an early commit to reach.
     */
    private void commitAtCommitPoint() {
        if (commitOnCallerThread) {
            commitDurably();
        } else {
            scheduleCommit();
        }
    }

    /** Queues a commit unless one is already queued, so a run of recordings asks for one rather than many. */
    private void scheduleCommit() {
        if (commitScheduled.compareAndSet(false, true)) {
            if (submit(this::runScheduledCommit) == null) {
                commitScheduled.set(false);
            }
        }
    }

    /**
     * Clears the scheduling flag before committing, not after, so events recorded while this runs can queue
     * a commit of their own rather than waiting for the next one to be triggered.
     */
    private void runScheduledCommit() {
        commitScheduled.set(false);
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
            // Both taken at once, so that an evaluation's counter and its full event are staged by the
            // same commit. Taken separately, a commit landing between the two writes one evaluation makes
            // stages the counter and leaves the event for the next one -- or, at close, for none at all.
            synchronized (recordLock) {
                run = pending.isEmpty() ? Collections.<Event>emptyList() : new ArrayList<>(pending);
                pending.clear();
                summaries = eventBuffer.takeSummaries();
                eventsBeingStaged = run.size();
            }
            try {
                stageRun(run);
                stageSummaries(summaries);
            } finally {
                synchronized (recordLock) {
                    eventsBeingStaged = 0;
                }
            }
            store.commit();
        }
    }

    /**
     * Encodes a run of events as one run and stages the bytes.
     * <p>
     * The run was taken under {@link #recordLock} and is encoded outside it, so recording does not wait on
     * the encoder. Staging bypasses capacity because the decision to keep these events was already made in
     * {@link #record}, and refusing them here would drop events the SDK has counted as accepted.
     * <p>
     * Requires {@link #commitLock}: two threads staging separate runs would stage them in whichever order
     * they finished encoding, which is not the order they were recorded in.
     */
    private void stageRun(List<Event> run) {
        for (byte[] serialized : eventBuffer.serializeAll(run)) {
            store.stage(serialized, true);
        }
    }

    /**
     * Turns the evaluation counters already taken into summary events in the store.
     */
    private void stageSummaries(List<EventSummarizer.EventSummary> summaries) {
        for (byte[] summary : eventBuffer.serializeSummaries(summaries)) {
            // Bypassing capacity: a summary is not a new event, it is the record of evaluations already
            // counted, and dropping it would lose all of them at once.
            store.stage(summary, true);
        }
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
        if (pending.size() + eventsBeingStaged + store.getPendingEventCount() >= capacity) {
            if (capacityExceeded.compareAndSet(false, true)) {
                logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
            }
            droppedEvents.incrementAndGet();
            return;
        }
        capacityExceeded.set(false);
        pending.add(event);
    }

    /**
     * Counts an evaluation the summarizer turned away, which it does when counting it would have meant
     * holding a context beyond the configured capacity.
     * <p>
     * A refused evaluation is a loss in the same sense a refused event is -- nothing later reconstructs
     * a counter -- so it is reported the same way, through the dropped count diagnostics carry.
     */
    private void reportContextsExceeded() {
        if (summaryContextsExceeded.compareAndSet(false, true)) {
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
    }

    @Override
    public void flush() {
        if (isStopped()) {
            return;
        }
        // A commit point, written on the caller's thread only where the application asked for that.
        // Otherwise the write is queued ahead of the delivery, so it still happens when the delivery
        // cannot run because the client is offline.
        commitAtCommitPoint();
        submit(this::deliverPayload);
    }

    @Override
    public void blockingFlush() {
        if (isStopped()) {
            return;
        }
        // The write is part of the task waited on rather than done first: the caller waits either way,
        // and this way the disk is touched on the events thread instead of the caller's.
        Future<?> delivery = submit(this::commitAndDeliver);
        if (delivery == null) {
            return;
        }
        try {
            delivery.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logUnexpectedError(e.getCause() == null ? e : e.getCause());
        }
    }

    @Override
    public boolean blockingFlush(long timeout, TimeUnit unit) {
        if (isStopped()) {
            return false;
        }
        // Typed rather than inlined, so that it is unambiguously submitted as work with a result.
        Callable<Boolean> delivery = this::commitAndDeliverReportingOutcome;
        Future<Boolean> pending = submit(delivery);
        if (pending == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(pending.get(timeout, unit));
        } catch (TimeoutException e) {
            // Left running rather than cancelled: the buffer has already been drained into the
            // payload, so interrupting the delivery now would only make the loss certain.
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            logUnexpectedError(e.getCause() == null ? e : e.getCause());
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (stateLock) {
            flushTask = enableOrDisableTask(false, flushTask, 0, null);
            diagnosticTask = enableOrDisableTask(false, diagnosticTask, 0, null);
        }
        // Deliver what is still buffered before we let go of the sender. This waits rather than
        // firing and forgetting because it is this run's last chance to send them. While offline
        // that chance is not taken: offline is the application telling the SDK to stay off the
        // network, and shutting down does not revoke that. The commit ahead of it still writes those
        // events down where persistence is on, so they go out on a later run; where it is off they
        // are discarded.
        final AtomicBoolean finalCommitDone = new AtomicBoolean(false);
        Future<?> delivery = submit(() -> {
            commitDurably();
            finalCommitDone.set(true);
            deliverPayload();
        });
        if (delivery != null) {
            try {
                delivery.get(closeBudgetMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Deliberately not cancelled. The run has already been drained into a payload, so
                // interrupting now would make the loss certain, while leaving it to run costs
                // nothing: the scheduler thread is a daemon, and returning from close() does not
                // end an Android process. The budget bounds the caller, not the delivery.
                logger.warn("Gave up waiting for the final event delivery after {}ms;" +
                        " it continues in the background", closeBudgetMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                logUnexpectedError(e.getCause() == null ? e : e.getCause());
            }
        }
        // A caller who closed the client and then let the process end has to find those events on the
        // next run, so the write must have happened by the time close() returns. It normally has, on
        // the events thread; this writes on the caller's only when that thread never got to it, such
        // as when it is still stuck on an earlier delivery.
        if (!finalCommitDone.get()) {
            commitDurably();
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
            queueRelease(scheduler, this::releaseDeliveryResources);
            queueRelease(diagnosticExecutor, this::releaseDiagnosticResources);
            scheduler.shutdown();
            diagnosticExecutor.shutdown();
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
     * Serializes and sends everything buffered, for a caller that is not waiting to find out how it
     * went.
     */
    private void deliverPayload() {
        deliverPayloadReportingOutcome();
    }

    /**
     * Writes everything accepted so far, then delivers. The write comes first because a delivery
     * refused for being offline returns before writing anything.
     */
    private void commitAndDeliver() {
        commitAndDeliverReportingOutcome();
    }

    private boolean commitAndDeliverReportingOutcome() {
        commitDurably();
        return deliverPayloadReportingOutcome();
    }

    /**
     * Delivers as {@link #deliverPayload()} does, and says whether it worked, for a caller that is
     * waiting to find out.
     * <p>
     * Runs on the scheduler thread, which is single-threaded, so only one payload is ever in flight
     * and the run is taken exactly once per delivery. The run and the counters are taken together
     * under {@link #recordLock}, so an evaluation is never split across two payloads, and encoded
     * outside it, so recording does not wait on the encoder.
     *
     * @return true if the events reached the service, or if there were none to send; false if they
     *   could not be sent or the service did not accept them
     */
    private boolean deliverPayloadReportingOutcome() {
        if (disabled || offline.get()) {
            return false;
        }

        commitDurably();
        store.closeBatch();

        // Every batch, not just the one just closed: the others are deliveries an earlier attempt did not
        // finish, or that a previous run of the application never got to start.
        boolean allDelivered = true;
        for (EventStore.Batch batch : store.pendingBatches()) {
            if (disabled || offline.get()) {
                return false;
            }
            allDelivered &= deliver(batch);
        }
        return allDelivered;
    }

    /**
     * @return true if the batch is no longer the SDK's problem, whether because it arrived or because it
     *   never can
     */
    private boolean deliver(EventStore.Batch batch) {
        byte[] body = store.body(batch);
        if (body == null) {
            // Unreadable, or already delivered by another process of this application. Either way there
            // is nothing to send and nothing to keep.
            store.remove(batch);
            return true;
        }
        if (diagnosticStore != null) {
            diagnosticStore.recordEventsInBatch(batch.eventCount);
        }
        try {
            EventSender.Result result = analyticsEventSender.sendBatch(body, batch.eventCount,
                    batch.payloadId, eventsUri);
            handleResponse(result);
            if (result != null && (result.isSuccess() || result.isMustShutDown())) {
                // Forgotten once the service has either taken the events or told us to stop sending
                // them. Anything else - a timeout, a 503, no network - leaves the batch where it is, to
                // be tried again on the next flush or the next run of the application.
                store.remove(batch);
                return result.isSuccess();
            }
            return false;
        } catch (Exception e) {
            logUnexpectedError(e);
            return false;
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
                // retries it. That matches DefaultEventProcessor, which is the behaviour to keep:
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
            // than after. createEventAndReset hands back the period and clears it -- the stream inits,
            // the events-in-batch count and the period start all move -- and getAndClearDroppedCount
            // does the same for the dropped count. This runs on the diagnostics thread, which may have
            // been busy with an earlier post for as long as that post took, so the state can easily
            // have changed since the checks above. Bailing out after the event was built would discard
            // a period outright and leave the next event describing a window that begins after the
            // reset; bailing out here leaves everything where it is, for the next period to carry.
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
     * telemetry, a post can take tens of seconds against a network that never answers, and queueing
     * would let an outage accumulate events describing an SDK state the application has since moved
     * past -- the same reasoning that makes a failed init event final rather than retried.
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
     * not enough on its own. Cancelling does not stop a run already underway, and the init event is
     * submitted before it reaches the executor. Either can arrive here after the state changed.
     */
    private boolean diagnosticsSuspended() {
        return isStopped() || offline.get() || inBackground.get();
    }

    private void handleResponse(EventSender.Result result) {
        if (result == null) {
            return;
        }
        if (result.getTimeFromServer() != null) {
            recordPastTime(result.getTimeFromServer().getTime());
        }
        if (result.isMustShutDown()) {
            disabled = true;
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

    private void updateScheduledTasks(boolean inBackground, boolean offline) {
        // The only close check the scheduling path needs, and the reason setOffline and
        // setInBackground do not carry one of their own. close() sets the flag before
        // it takes stateLock and cancels the tasks under it, so whichever of the two reaches the lock
        // second sees what the other did: either this returns here, or it schedules and close() then
        // cancels what it scheduled. Two threads cannot both get past this and leave a task running.
        if (closed.get()) {
            return;
        }
        // Flushing stays scheduled whether or not we are offline or in the background; a run while
        // offline returns without doing anything. Cancelling it for an outage would restart the
        // interval on every reconnect, and a run of brief outages would then hold events back for
        // far longer than one interval. Left running, what an outage buffered goes out at the first
        // run after it ends.
        flushTask = enableOrDisableTask(true, flushTask, flushIntervalMillis,
                this::deliverPayload);
        // Events a previous run left on disk have already waited out a whole process, so they do not
        // wait out an interval as well. An application that dies within one interval of every start
        // would otherwise never deliver them.
        if (!offline && hasEventsFromPreviousRun.compareAndSet(true, false)) {
            submit(this::deliverPayload);
        }
        boolean diagnosticsEnabled = diagnosticStore != null && !offline && !inBackground;
        diagnosticTask = enableOrDisableTask(diagnosticsEnabled, diagnosticTask,
                diagnosticRecordingIntervalMillis, this::sendDiagnosticStats);
        if (diagnosticsEnabled && !diagnosticInitSent && claimDiagnosticPost()) {
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
            // logging the first: the executor marks the repeating future done and never fires it
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
     * As {@link #submit(Runnable)}, for a delivery whose outcome the caller waits for. Not wrapped in
     * {@link #guarded}, because here the caller is there to receive what escapes.
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
     * so this catches {@code Throwable} and not just {@code Exception}: a
     * {@code StackOverflowError} from nested {@code LDValue} data or an {@code OutOfMemoryError}
     * growing the payload stream would otherwise stop flushing for the life of the process with
     * nothing logged.
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
        return closed.get() || disabled;
    }

    private void logUnexpectedError(Throwable e) {
        logger.error("Unexpected error in event processor: {}", LogValues.exceptionSummary(e));
        logger.debug("{}", LogValues.exceptionTrace(e));
    }
}
