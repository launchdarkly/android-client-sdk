package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.events.DiagnosticEvent;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.EventSender;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends the SDK's diagnostic events: the init event once per process, and the statistics once per recording
 * interval, only while the SDK is online and in the foreground.
 * <p>
 * Part of {@link DirectEventProcessor}, which tells it through {@link #setMode} when that changes and when the
 * launch gate the init event waits for opens. Diagnostics are best-effort telemetry about the SDK, so nothing
 * here is stored or retried. Posts go out one at a time on a thread of their own, never the one delivering
 * analytics events.
 */
final class DiagnosticReporter {
    /** What the reporter needs from the event processor whose events it describes. */
    interface Processor {
        /** @return the events dropped since this was last called; see {@link DirectEventProcessor#getAndClearDroppedCount()} */
        long getAndClearDroppedCount();

        /** Hears the service's clock, which a diagnostic response carries as an analytics one does. */
        void recordPastTime(long timeFromServer);
    }

    private final DiagnosticStore store;
    private final EventSender sender;
    private final URI eventsUri;
    private final long recordingIntervalMillis;
    /** Where the statistics task runs: the event processor's scheduler, which only hands each post on. */
    private final ScheduledExecutorService scheduler;
    private final ExecutorService postExecutor;
    private final Processor processor;
    private final LDLogger logger;

    /** Only touched in {@link #setMode}, which the event processor calls under its state lock. */
    private ScheduledFuture<?> statsTask;

    /** Whether diagnostics may go out now. Written in {@link #setMode}, read on the posting thread. */
    private volatile boolean enabled;
    private volatile boolean initSent = false;

    /**
     * True while a diagnostic event is on its way to the service, so that a later one is dropped
     * rather than queued behind it.
     */
    private final AtomicBoolean postInFlight = new AtomicBoolean(false);

    /**
     * @param store the diagnostics to report, or null where the application opted out of them
     */
    DiagnosticReporter(
            DiagnosticStore store,
            EventSender sender,
            URI eventsUri,
            long recordingIntervalMillis,
            ScheduledExecutorService scheduler,
            ExecutorService postExecutor,
            Processor processor,
            LDLogger logger
    ) {
        this.store = store;
        this.sender = sender;
        this.eventsUri = eventsUri;
        this.recordingIntervalMillis = recordingIntervalMillis;
        this.scheduler = scheduler;
        this.postExecutor = postExecutor;
        this.processor = processor;
        this.logger = logger;
    }

    /**
     * Starts or stops reporting, and sends the init event once it may go. Called under the event processor's
     * state lock, so calls arrive in the order the changes happened.
     *
     * @param online whether the event processor is open, online and in the foreground
     * @param launchGateOpen whether the launch gate has opened, which the init event waits for
     */
    void setMode(boolean online, boolean launchGateOpen) {
        enabled = online && store != null;
        statsTask = EventUtil.enableOrDisableTask(scheduler, enabled, statsTask, recordingIntervalMillis,
                guarded(this::sendStats));
        if (enabled && launchGateOpen && !initSent && claimPost()) {
            DiagnosticStore store = this.store;
            post(() -> {
                // Re-checked on the posting thread: going online and coming to the foreground are
                // two separate calls, and both want to send the init event we never got to send.
                // Suspension is re-checked for the same reason it is for the periodic event, and
                // costs nothing here: getInitEvent consumes nothing, so a skipped init is simply
                // built again the next time diagnostics are enabled.
                if (!initSent && enabled) {
                    send(store.getInitEvent(), true);
                }
            });
        }
    }

    /** Counts a batch of analytics events sent, for the next statistics event. */
    void recordEventsInBatch(int eventCount) {
        if (store != null) {
            store.recordEventsInBatch(eventCount);
        }
    }

    /**
     * Releases the sender on the posting thread, after any post still on it, and stops that thread taking
     * more. The statistics task is canceled by the {@link #setMode} that closing the event processor makes.
     */
    void close() {
        try {
            postExecutor.submit(guarded(this::closeSender));
        } catch (RuntimeException e) { // the executor was shut down under us
            closeSender(); // so nothing can still be using it
        }
        postExecutor.shutdown();
    }

    private void closeSender() {
        try {
            sender.close();
        } catch (IOException e) {
            logUnexpectedError(e);
        }
    }

    private void sendStats() {
        // Diagnostics are not sent while offline or in the background. Canceling the task when either
        // becomes true is not enough on its own: canceling does not stop a run already underway, and
        // the init event is submitted before it reaches the executor. Either can get here after the
        // state changed, so both test enabled again.
        if (!enabled || !claimPost()) {
            return;
        }
        DiagnosticStore store = this.store;
        post(() -> {
            // The check that decides the outcome, and it has to come before the event is built rather
            // than after. createEventAndReset hands back the period and clears it: the stream init
            // records, the events-in-batch count and the period start all move. getAndClearDroppedCount
            // does the same for the dropped count. This runs on the posting thread, which may have
            // been busy with an earlier post for as long as that post took, so the state can easily
            // have changed since the check above. Bailing out after the event was built would discard
            // a period outright and leave the next event describing a window that begins after the
            // reset. Bailing out here leaves everything where it is, for the next period to carry.
            if (!enabled) {
                return;
            }
            send(store.createEventAndReset(processor.getAndClearDroppedCount(), 0), false);
        });
    }

    /**
     * Posts an event that has already been built. Requires the caller to have checked {@link #enabled},
     * because for the periodic event building it is what consumes the period.
     */
    private void send(DiagnosticEvent event, boolean isInit) {
        try {
            byte[] data = event.getJsonValue().toJsonString().getBytes(StandardCharsets.UTF_8);
            EventSender.Result result = sender.sendDiagnosticEvent(data, eventsUri);
            // Only read for the service's clock. One the service refused stops nothing: analytics delivery
            // backs off on its own responses, and a diagnostic is not retried anyway.
            if (result != null && result.getTimeFromServer() != null) {
                processor.recordPastTime(result.getTimeFromServer().getTime());
            }
            if (isInit) {
                // Attempted, not delivered. A failed post gives back an unsuccessful Result rather
                // than throwing, so this marks the init event done either way and the process never
                // retries it. That matches DefaultEventProcessor, which is the behavior to keep:
                // diagnostics are best-effort telemetry about the SDK, and a retry that outlived
                // its own init would describe a configuration the application has moved on from.
                initSent = true;
            }
        } catch (Exception e) {
            logUnexpectedError(e);
        }
    }

    /**
     * Takes the posting thread, or reports that the last event is still on it.
     * <p>
     * A new event is dropped rather than queued behind the old one. Diagnostics are best-effort
     * telemetry, and a post can take tens of seconds against a network that never answers. Queuing
     * would let an outage accumulate events describing an SDK state the application has since moved
     * past. The same reasoning makes a failed init event final rather than retried.
     */
    private boolean claimPost() {
        if (postInFlight.compareAndSet(false, true)) {
            return true;
        }
        logger.debug("Skipped a diagnostic event because the previous one is still being posted");
        return false;
    }

    /**
     * Hands a claimed post to the posting thread, releasing the claim once it ends.
     *
     * @param post what to run there, which must hold a claim from {@link #claimPost()}
     */
    private void post(Runnable post) {
        Runnable releasing = () -> {
            try {
                post.run();
            } finally {
                postInFlight.set(false);
            }
        };
        try {
            postExecutor.submit(guarded(releasing));
        } catch (RuntimeException e) { // the executor was shut down under us
            postInFlight.set(false);
        }
    }

    private Runnable guarded(Runnable task) {
        return EventUtil.guarded(logger, task);
    }

    private void logUnexpectedError(Throwable e) {
        EventUtil.logUnexpectedError(logger, e);
    }
}
