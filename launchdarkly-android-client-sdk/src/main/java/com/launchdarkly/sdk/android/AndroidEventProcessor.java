package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.internal.events.AndroidEventBuffer;
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
final class AndroidEventProcessor implements EventProcessor {
    private final AndroidEventBuffer buffer;
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

    AndroidEventProcessor(
            AndroidEventBuffer buffer,
            EventSender eventSender,
            URI eventsUri,
            DiagnosticStore diagnosticStore,
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
            buffer.addFullEvent(event);
        }
        if (shouldDebugEvent(debugEventsUntilDate)) {
            buffer.addFullEvent(event.toDebugEvent());
        }
    }

    @Override
    public void recordIdentifyEvent(LDContext context) {
        if (isStopped() || context == null) {
            return;
        }
        buffer.addFullEvent(new Event.Identify(System.currentTimeMillis(), context));
    }

    @Override
    public void recordCustomEvent(LDContext context, String eventKey, LDValue data, Double metricValue) {
        if (isStopped() || context == null) {
            return;
        }
        buffer.addFullEvent(new Event.Custom(System.currentTimeMillis(), eventKey, context, data,
                metricValue));
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
    public boolean blockingFlush(long timeout, TimeUnit unit) {
        if (isStopped()) {
            return false;
        }
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
        eventSender.close();
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
        AndroidEventBuffer.Payload payload;
        try {
            payload = buffer.drain();
        } catch (IOException e) {
            logUnexpectedError(e);
            return false;
        }
        if (payload == null) {
            return true;
        }
        if (diagnosticStore != null) {
            diagnosticStore.recordEventsInBatch(payload.getEventCount());
        }
        try {
            EventSender.Result result = eventSender.sendAnalyticsEvents(payload.getData(),
                    payload.getEventCount(), eventsUri);
            handleResponse(result);
            return result != null && result.isSuccess();
        } catch (Exception e) {
            logUnexpectedError(e);
            return false;
        }
    }

    private void sendDiagnosticEvent(DiagnosticEvent diagnosticEvent, boolean isInit) {
        if (disabled.get()) {
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
        if (disabled.get() || diagnosticStore == null) {
            return;
        }
        sendDiagnosticEvent(diagnosticStore.createEventAndReset(buffer.getAndClearDroppedCount(), 0),
                false);
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
