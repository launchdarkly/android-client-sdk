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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Android SDK's analytics event processor.
 * <p>
 * Recording an event summarizes it immediately and, only if it has to be delivered in full,
 * buffers it. There is no queue between the calling thread and the summarizer, so a burst of flag
 * evaluations with trackEvent = false cannot displace anything and costs a counter increment,
 * The configured capacity limits only the events that have trackEvents = true.
 */
final class DirectEventProcessor implements EventProcessor {
    private final OutboundEventBuffer buffer;
    private final EventSender eventSender;
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

    private final Object stateLock = new Object();
    private ScheduledFuture<?> flushTask;
    private ScheduledFuture<?> diagnosticTask;

    /**
     * Full events recorded but not yet encoded, guarded by {@link #pendingLock}.
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
     * How many events the SDK will hold between flushes.
     */
    private final int capacity;

    private final AtomicBoolean capacityExceeded = new AtomicBoolean(false);
    private final AtomicLong droppedEvents = new AtomicLong(0);

    DirectEventProcessor(
            OutboundEventBuffer buffer,
            EventSender eventSender,
            URI eventsUri,
            DiagnosticStore diagnosticStore,
            int capacity,
            long flushIntervalMillis,
            long diagnosticRecordingIntervalMillis,
            boolean initiallyInBackground,
            boolean initiallyOffline,
            ScheduledExecutorService scheduler,
            LDLogger logger
    ) {
        this.buffer = buffer;
        this.eventSender = eventSender;
        this.eventsUri = eventsUri;
        this.diagnosticStore = diagnosticStore;
        this.capacity = capacity >= 0 ? capacity : 1;
        this.flushIntervalMillis = flushIntervalMillis;
        this.diagnosticRecordingIntervalMillis = diagnosticRecordingIntervalMillis;
        this.scheduler = scheduler;
        this.logger = logger;
        this.inBackground = new AtomicBoolean(initiallyInBackground);
        this.offline = new AtomicBoolean(initiallyOffline);

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
        buffer.summarize(event);
        if (requireFullEvent) {
            record(event);
        }
        if (shouldDebugEvent(debugEventsUntilDate)) {
            record(event.toDebugEvent());
        }
    }

    @Override
    public void recordIdentifyEvent(LDContext context) {
        if (isStopped() || context == null) {
            return;
        }
        record(new Event.Identify(System.currentTimeMillis(), context));
    }

    @Override
    public void recordCustomEvent(LDContext context, String eventKey, LDValue data, Double metricValue) {
        if (isStopped() || context == null) {
            return;
        }
        record(new Event.Custom(System.currentTimeMillis(), eventKey, context, data, metricValue));
    }

    /**
     * Holds an event for the next flush to encode, counting it as dropped if the SDK is already full.
     * <p>
     * Capacity is consulted before anything else, so an event that will not be kept is never encoded.
     * That ordering is what bounds an application re-evaluating a tracked flag in a render loop: once
     * the limit is reached the cost of an evaluation falls back to its summary counter, however fast
     * the loop runs.
     */
    void record(Event event) {
        // Ahead of the capacity check, because an event the SDK was never going to send is not a
        // loss and must not be counted as one. Sampling and capacity are different reasons not to
        // keep an event, and only the second is one the SDK owes anyone a count of.
        if (!Sampler.shouldSample(event.getSamplingRatio())) {
            return;
        }
        synchronized (pendingLock) {
            if (pending.size() >= capacity) {
                if (capacityExceeded.compareAndSet(false, true)) {
                    logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
                }
                droppedEvents.incrementAndGet();
                return;
            }
            capacityExceeded.set(false);
            pending.add(event);
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
        submit(this::deliverPayload);
    }

    @Override
    public void blockingFlush() {
        if (isStopped()) {
            return;
        }
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
        eventSender.close();
    }

    /**
     * Serializes and sends everything buffered. Runs on the scheduler thread, which is
     * single-threaded, so only one payload is ever in flight and the run is taken exactly once per
     * delivery.
     * <p>
     * The run is taken under {@link #pendingLock} and encoded outside it, so recording does not wait
     * on the encoder.
     */
    private void deliverPayload() {
        if (disabled.get() || offline.get()) {
            return;
        }
        List<Event> run;
        synchronized (pendingLock) {
            run = pending.isEmpty() ? Collections.<Event>emptyList() : new ArrayList<>(pending);
            pending.clear();
        }
        OutboundEventBuffer.Payload payload;
        try {
            payload = buffer.drain(run);
        } catch (IOException e) {
            logUnexpectedError(e);
            return;
        }
        if (payload == null) {
            return;
        }
        if (diagnosticStore != null) {
            diagnosticStore.recordEventsInBatch(payload.getEventCount());
        }
        try {
            handleResponse(eventSender.sendAnalyticsEvents(payload.getData(),
                    payload.getEventCount(), eventsUri));
        } catch (Exception e) {
            logUnexpectedError(e);
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

    private Future<?> submit(Runnable task) {
        try {
            return scheduler.submit(guarded(task));
        } catch (RuntimeException e) { // the executor was shut down under us
            return null;
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
        return closed.get() || disabled.get();
    }

    private void logUnexpectedError(Throwable e) {
        logger.error("Unexpected error in event processor: {}", LogValues.exceptionSummary(e));
        logger.debug("{}", LogValues.exceptionTrace(e));
    }
}
