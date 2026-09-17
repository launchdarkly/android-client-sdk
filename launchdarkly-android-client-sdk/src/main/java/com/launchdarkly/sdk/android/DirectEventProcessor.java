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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
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
 * Recording an event summarizes it immediately and, only if it has to be delivered in full,
 * buffers it. There is no queue between the calling thread and the summarizer, so a burst of flag
 * evaluations cannot displace anything: evaluations of untracked flags cost a counter increment,
 * and the configured capacity limits only the events that genuinely have to be sent one by one.
 */
final class DirectEventProcessor implements EventProcessor {
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
    private final AtomicBoolean capacityExceeded = new AtomicBoolean(false);
    private final AtomicLong droppedEvents = new AtomicLong(0);
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

    DirectEventProcessor(
            OutboundEventBuffer eventBuffer,
            EventStore store,
            EventSender eventSender,
            AnalyticsEventSender analyticsEventSender,
            URI eventsUri,
            DiagnosticStore diagnosticStore,
            long flushIntervalMillis,
            long diagnosticRecordingIntervalMillis,
            boolean initiallyInBackground,
            boolean initiallyOffline,
            ScheduledExecutorService scheduler,
            LDLogger logger
    ) {
        this.eventBuffer = eventBuffer;
        this.store = store;
        this.eventSender = eventSender;
        this.analyticsEventSender = analyticsEventSender;
        this.eventsUri = eventsUri;
        this.diagnosticStore = diagnosticStore;
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
        commitDurably();
    }

    @Override
    public void recordCustomEvent(LDContext context, String eventKey, LDValue data, Double metricValue) {
        if (isStopped() || context == null) {
            return;
        }
        record(new Event.Custom(System.currentTimeMillis(), eventKey, context, data, metricValue));
        commitDurably();
    }

    /**
     * Serializes an event and stages it, counting it as dropped if the store is full.
     */
    private void record(Event event) {
        byte[] serialized = eventBuffer.serialize(event);
        if (serialized == null) {
            return; // sampled out, or unserializable, and already logged
        }
        if (!store.stage(serialized)) {
            if (capacityExceeded.compareAndSet(false, true)) {
                logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
            }
            droppedEvents.incrementAndGet();
            return;
        }
        capacityExceeded.set(false);
    }

    /**
     * Makes everything recorded so far survive the process, on the caller's thread.
     * <p>
     * This is the point of the whole arrangement, so it is worth being precise about which callers get
     * it: those recording an event the application asked for by name. An application that calls
     * {@code track} to report an error is telling the SDK that this event matters more than the
     * microseconds it costs to write it, and the crash it is reporting may be moments away.
     * <p>
     * The evaluations counted since the last commit point are written too, because a summary is only worth
     * having if it covers the evaluations that led up to whatever is about to happen.
     */
    private void commitDurably() {
        stageSummaries();
        store.commit();
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
        // client and then let the process end still has those events on the next run.
        store.close();
        eventSender.close();
        analyticsEventSender.close();
    }

    /**
     * Serializes and sends everything buffered. Runs on the scheduler thread, so only one payload
     * is ever in flight and the buffer is drained exactly once per delivery.
     */
    private void deliverPayload() {
        deliverPayloadReportingOutcome();
    }

    /**
     * Delivers as {@link #deliverPayload()} does, and says whether it worked, for a caller that is
     * waiting to find out.
     *
     * @return true if the events reached the service, or if there were none to send; false if they
     *   could not be sent or the service did not accept them
     */
    private boolean deliverPayloadReportingOutcome() {
        if (disabled.get() || offline.get()) {
            return false;
        }

        stageSummaries();
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
                    .getBytes(Charset.forName("UTF-8"));
            handleResponse(eventSender.sendDiagnosticEvent(data, eventsUri));
            if (isInit) {
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
        sendDiagnosticEvent(diagnosticStore.createEventAndReset(droppedEvents.getAndSet(0), 0),
                false);
    }

    /**
     * Unlike analytics events, diagnostics are not sent while offline or in the background.
     * {@link #updateScheduledTasks} cancels the periodic task when either becomes true, but
     * cancelling does not stop a run that has already begun, and the init event is submitted before
     * it reaches the executor, so both paths can still arrive here after the state has changed.
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
            return scheduler.scheduleAtFixedRate(guarded(task), intervalMillis, intervalMillis,
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
