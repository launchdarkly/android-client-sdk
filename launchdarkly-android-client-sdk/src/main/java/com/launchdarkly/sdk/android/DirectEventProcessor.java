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
import com.launchdarkly.sdk.internal.events.Sampler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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

    private final OutboundEventBuffer eventBuffer;
    private final EventStore store;
    private final EventSender eventSender;
    private final AnalyticsEventSender analyticsEventSender;
    private final URI eventsUri;
    private final DiagnosticStore diagnosticStore;
    private final long flushIntervalMillis;
    private final long diagnosticRecordingIntervalMillis;
    private final ScheduledExecutorService scheduler;
    private final LDLogger logger;

    private final AtomicBoolean inBackground;
    private final AtomicBoolean offline;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    // Set when the service tells us to stop, e.g. because the mobile key is invalid.
    private final AtomicBoolean disabled = new AtomicBoolean(false);
    private final AtomicBoolean diagnosticInitSent = new AtomicBoolean(false);
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
     * Full events recorded but not yet encoded, guarded by {@link #pendingLock}.
     * <p>
     * Held rather than serialized because serializing early would not make them durable: the store stages
     * bytes into memory too, and only {@link EventStore#commit()} reaches the file. Both forms are equally
     * lost to a crash, so the encode may as well happen where it is cheapest.
     */
    private final List<Event> pending = new ArrayList<>();

    /**
     * Guards {@link #pending}, and is held for one list append or one handover of the run -- never
     * across the encode.
     * <p>
     * Recording runs on whichever thread evaluated a flag, which on Android is usually the main one.
     * The worst it may wait for is another thread's memory operation; if the encoder ran under this
     * lock, an evaluation would instead wait on the dominant cost of the whole path.
     */
    private final Object pendingLock = new Object();

    /**
     * Held for the whole of a commit, so that only one runs at a time.
     * <p>
     * This is what {@code track}'s guarantee rests on. Without it a commit already in flight could take the
     * caller's event out of {@link #pending} before the caller got there, leaving the caller nothing to
     * write and returning while those bytes were still being produced somewhere else. Waiting here instead
     * means that when the call returns the event is on disk, whichever commit put it there.
     * <p>
     * Distinct from {@link #pendingLock}, which is only held long enough to hand the run over: encoding
     * under this lock must not block a thread that is merely recording.
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
    private final AtomicLong droppedEvents = new AtomicLong(0);

    /**
     * Whether a commit point encodes and writes before returning, rather than queueing that work.
     */
    private final boolean commitOnCallerThread;

    DirectEventProcessor(
            OutboundEventBuffer eventBuffer,
            EventStore store,
            EventSender eventSender,
            AnalyticsEventSender analyticsEventSender,
            URI eventsUri,
            DiagnosticStore diagnosticStore,
            int capacity,
            boolean commitOnCallerThread,
            long flushIntervalMillis,
            long diagnosticRecordingIntervalMillis,
            boolean initiallyInBackground,
            boolean initiallyOffline,
            ScheduledExecutorService scheduler,
            LDLogger logger
    ) {
        this.eventBuffer = eventBuffer;
        this.store = store;
        this.commitOnCallerThread = commitOnCallerThread;
        this.eventSender = eventSender;
        this.analyticsEventSender = analyticsEventSender;
        this.eventsUri = eventsUri;
        this.diagnosticStore = diagnosticStore;
        this.capacity = capacity >= 0 ? capacity : 1;
        this.flushIntervalMillis = flushIntervalMillis;
        this.diagnosticRecordingIntervalMillis = diagnosticRecordingIntervalMillis;
        this.scheduler = scheduler;
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
        eventBuffer.summarize(event);
        if (requireFullEvent) {
            record(event);
        }
        if (shouldDebugEvent(debugEventsUntilDate)) {
            record(event.toDebugEvent());
        }
        // Deliberately no commit. An evaluation is expected to cost what a map lookup costs, and it is
        // usually the main thread doing it; the store writes these on its own thread once enough of them
        // have piled up, and the next event recorded at a commit point makes them durable along with itself.
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
     * Capacity is consulted before anything else, so an event that will not be kept is never encoded. That
     * ordering is what bounds an application re-evaluating a tracked flag in a render loop: once the limit is
     * reached the cost of an evaluation falls back to its summary counter, however fast the loop runs.
     */
    void record(Event event) {
        // Ahead of the capacity check, because an event the SDK was never going to send is not a
        // loss and must not be counted as one. Sampling and capacity are different reasons not to
        // keep an event, and only the second is one the SDK owes anyone a count of.
        if (!Sampler.shouldSample(event.getSamplingRatio())) {
            return;
        }
        boolean needsCommit;
        synchronized (pendingLock) {
            if (pending.size() + store.getPendingEventCount() >= capacity) {
                if (capacityExceeded.compareAndSet(false, true)) {
                    logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
                }
                droppedEvents.incrementAndGet();
                return;
            }
            capacityExceeded.set(false);
            pending.add(event);
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
            stagePendingEvents();
            stageSummaries();
            store.commit();
        }
    }

    /**
     * Encodes the held events as one run and stages the bytes.
     * <p>
     * The run is taken under {@link #pendingLock} and encoded outside it, so recording does not wait on the
     * encoder. Staging bypasses capacity because the decision to keep these events was already made in
     * {@link #record}, and refusing them here would drop events the SDK has counted as accepted.
     * <p>
     * Requires {@link #commitLock}: two threads draining separate runs would stage them in whichever order
     * they finished encoding, which is not the order they were recorded in.
     */
    private void stagePendingEvents() {
        List<Event> run;
        synchronized (pendingLock) {
            if (pending.isEmpty()) {
                return;
            }
            run = new ArrayList<>(pending);
            pending.clear();
        }
        for (byte[] serialized : eventBuffer.serializeAll(run)) {
            store.stage(serialized, true);
        }
    }

    /**
     * Turns the evaluation counters into summary events in the store.
     */
    private void stageSummaries() {
        for (byte[] summary : eventBuffer.serializeSummariesAndReset()) {
            // Bypassing capacity: a summary is not a new event, it is the record of evaluations already
            // counted, and dropping it would lose all of them at once.
            store.stage(summary, true);
        }
    }

    /**
     * @return the number of full events dropped for capacity since this was last called
     */
    long getAndClearDroppedCount() {
        return droppedEvents.getAndSet(0);
    }

    @Override
    public void setInBackground(boolean inBackground) {
        if (closed.get()) {
            return;
        }
        synchronized (stateLock) {
            if (this.inBackground.getAndSet(inBackground) == inBackground) {
                return;
            }
            updateScheduledTasks(inBackground, offline.get());
        }
    }

    @Override
    public void setOffline(boolean offline) {
        if (closed.get()) {
            return;
        }
        synchronized (stateLock) {
            if (this.offline.getAndSet(offline) == offline) {
                return;
            }
            updateScheduledTasks(inBackground.get(), offline);
            if (!offline) {
                // The periodic task was cancelled for the outage and starts a fresh interval above,
                // so anything the outage buffered would otherwise wait the whole of it. Worse, each
                // loss of connectivity re-anchors that interval, so a run of brief ones can hold
                // events back for far longer than a single interval.
                submit(this::deliverPayload);
            }
        }
    }

    @Override
    public void flush() {
        if (isStopped()) {
            return;
        }
        // Flush is a commit point: persist everything accepted before this call before returning,
        // even when the queued delivery cannot run because the client is offline.
        commitDurably();
        submit(this::deliverPayload);
    }

    @Override
    public void blockingFlush() {
        if (isStopped()) {
            return;
        }
        commitDurably();
        Future<?> delivery = submit(this::deliverPayload);
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
        commitDurably();
        // Typed rather than inlined, so that it is unambiguously submitted as work with a result.
        Callable<Boolean> delivery = this::deliverPayloadReportingOutcome;
        Future<Boolean> pending;
        try {
            pending = scheduler.submit(delivery);
        } catch (RuntimeException e) { // the executor was shut down under us
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
        // Deliver what is still buffered before we let go of the sender. The caller is entitled to
        // assume the events made it out once close() returns, so this waits rather than firing and
        // forgetting; it is the last chance these events get.
        Future<?> delivery = submit(this::deliverPayload);
        if (delivery != null) {
            try {
                delivery.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                logUnexpectedError(e.getCause() == null ? e : e.getCause());
            }
        }
        scheduler.shutdown();
        // Last: whatever could not be delivered is written down instead, so a caller who closed the
        // client and then let the process end still has those events on the next run. Encoding happens
        // here too, because a delivery that was refused for being offline returned before staging
        // anything and the held events are still only objects.
        commitDurably();
        store.close();
        eventSender.close();
        analyticsEventSender.close();
    }

    /**
     * Serializes and sends everything buffered, for a caller that is not waiting to find out how it
     * went.
     */
    private void deliverPayload() {
        deliverPayloadReportingOutcome();
    }

    /**
     * Delivers as {@link #deliverPayload()} does, and says whether it worked, for a caller that is
     * waiting to find out.
     * <p>
     * Runs on the scheduler thread, which is single-threaded, so only one payload is ever in flight
     * and the run is taken exactly once per delivery. The run is taken under {@link #pendingLock}
     * and encoded outside it, so recording does not wait on the encoder.
     *
     * @return true if the events reached the service, or if there were none to send; false if they
     *   could not be sent or the service did not accept them
     */
    private boolean deliverPayloadReportingOutcome() {
        if (disabled.get() || offline.get()) {
            return false;
        }

        commitDurably();
        store.closeBatch();

        // Every batch, not just the one just closed: the others are deliveries an earlier attempt did not
        // finish, or that a previous run of the application never got to start.
        boolean allDelivered = true;
        for (EventStore.Batch batch : store.pendingBatches()) {
            if (disabled.get() || offline.get()) {
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

    private void sendDiagnosticEvent(DiagnosticEvent diagnosticEvent, boolean isInit) {
        if (diagnosticsSuspended()) {
            return;
        }
        try {
            byte[] data = diagnosticEvent.getJsonValue().toJsonString()
                    .getBytes(StandardCharsets.UTF_8);
            handleResponse(eventSender.sendDiagnosticEvent(data, eventsUri));
            if (isInit) {
                // Attempted, not delivered. A failed post gives back an unsuccessful Result rather
                // than throwing, so this marks the init event done either way and the process never
                // retries it. That matches DefaultEventProcessor, which is the behaviour to keep:
                // diagnostics are best-effort telemetry about the SDK, and a retry that outlived
                // its own init would describe a configuration the application has moved on from.
                diagnosticInitSent.set(true);
            }
        } catch (Exception e) {
            logUnexpectedError(e);
        }
    }

    private void sendDiagnosticStats() {
        // Checked before createEventAndReset, which clears the counters it hands back: bailing out
        // after that call would discard a period's worth of statistics instead of deferring them.
        if (diagnosticsSuspended() || diagnosticStore == null) {
            return;
        }
        sendDiagnosticEvent(diagnosticStore.createEventAndReset(getAndClearDroppedCount(), 0),
                false);
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
            lastKnownPastTime.set(result.getTimeFromServer().getTime());
        }
        if (result.isMustShutDown()) {
            disabled.set(true);
        }
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
        if (closed.get()) {
            return;
        }
        // Flushing is pointless while we are offline, but it stays on in the background so that
        // events recorded before the app was backgrounded still get delivered.
        flushTask = enableOrDisableTask(!offline, flushTask, flushIntervalMillis,
                this::deliverPayload);
        if (!offline && hasEventsFromPreviousRun.compareAndSet(true, false)) {
            submit(this::deliverPayload);
        }
        boolean diagnosticsEnabled = diagnosticStore != null && !offline && !inBackground;
        diagnosticTask = enableOrDisableTask(diagnosticsEnabled, diagnosticTask,
                diagnosticRecordingIntervalMillis, this::sendDiagnosticStats);
        if (diagnosticsEnabled && !diagnosticInitSent.get()) {
            DiagnosticStore store = diagnosticStore;
            // Re-check on the executor thread: going online and coming to the foreground are two
            // separate calls, and both want to send the init event we never got to send.
            submit(() -> {
                if (!diagnosticInitSent.get()) {
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
        if (currentTask != null) {
            return currentTask;
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

    private Future<?> submit(Runnable task) {
        try {
            return scheduler.submit(guarded(task));
        } catch (RuntimeException e) { // the executor was shut down under us
            return null;
        }
    }

    /**
     * Keeps an unexpected failure from killing a repeating task or bubbling out of the executor.
     */
    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                logUnexpectedError(e);
            }
        };
    }

    private boolean isStopped() {
        return closed.get() || disabled.get();
    }

    private void logUnexpectedError(Throwable e) {
        logger.error("Unexpected error in event processor: {}", LogValues.exceptionSummary(e));
        logger.debug("{}", LogValues.exceptionTrace(e));
    }
}
