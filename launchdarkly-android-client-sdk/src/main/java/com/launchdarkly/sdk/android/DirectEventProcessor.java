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
import java.util.concurrent.atomic.AtomicInteger;
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

    private final OutboundEventBuffer buffer;
    private final EventSender eventSender;
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

    private final Object stateLock = new Object();
    private ScheduledFuture<?> flushTask;
    private ScheduledFuture<?> diagnosticTask;

    /**
     * Full events recorded but not yet encoded, guarded by {@link #recordLock}.
     */
    private final List<Event> pending = new ArrayList<>();

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
     * How many events the SDK will hold between flushes.
     */
    private final int capacity;

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
     * The two threads that post through {@link #eventSender}, counted down at shutdown so that
     * whichever finishes last is the one that closes it.
     */
    private final AtomicInteger sendersStillDraining = new AtomicInteger(2);

    /**
     * Guards the handover from running to shut down: held for the length of a submit, and by
     * {@link #close()} while it queues the release of the sender and stops the executors accepting
     * work. Nothing blocking happens under it.
     */
    private final Object submitLock = new Object();

    /** Set under {@link #submitLock} once close() has queued the release of the sender. */
    private boolean shuttingDown = false;

    DirectEventProcessor(
            OutboundEventBuffer buffer,
            EventSender eventSender,
            URI eventsUri,
            DiagnosticStore diagnosticStore,
            int capacity,
            long flushIntervalMillis,
            long diagnosticRecordingIntervalMillis,
            long closeBudgetMillis,
            boolean initiallyInBackground,
            boolean initiallyOffline,
            ScheduledExecutorService scheduler,
            ExecutorService diagnosticExecutor,
            LDLogger logger
    ) {
        this.buffer = buffer;
        this.eventSender = eventSender;
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
        synchronized (recordLock) {
            if (closed.get()) {
                return;
            }
            contextsExceeded = !buffer.summarize(event);
            if (requireFullEvent) {
                addPending(event);
            }
            if (debugEvent != null) {
                addPending(debugEvent);
            }
        }
        if (contextsExceeded) {
            reportContextsExceeded();
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
     * Capacity is consulted before anything is encoded, so an event that will not be kept is never
     * encoded.
     * That ordering is what bounds an application re-evaluating a tracked flag in a render loop: once
     * the limit is reached the cost of an evaluation falls back to its summary counter, however fast
     * the loop runs.
     */
    void record(Event event) {
        synchronized (recordLock) {
            // The close check that decides the outcome, as against the fast path the public record
            // methods take before building the event. deliverPayload lifts the run out under this
            // same lock, so testing the flag here orders a record against close()'s final delivery:
            // either the event is in the list before that delivery takes it, or it is refused.
            // Tested outside the lock the two interleave, and an event can be left in a list that
            // nothing will drain again.
            if (closed.get()) {
                return;
            }
            addPending(event);
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
            updateScheduledTasks(inBackground.get(), offline.get());
        }
        // Deliver what is still buffered before we let go of the sender. This waits rather than
        // firing and forgetting because it is the last chance these events get: nothing is kept
        // once the processor is gone. While offline that chance is not taken, and whatever is held
        // is discarded. Offline is the application telling the SDK to stay off the network, and
        // shutting down does not revoke that.
        Future<?> delivery = submit(this::deliverPayload);
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
        // Queued on both of the threads that post through the sender, so that it is released by
        // whichever of them finishes last. Closing it here instead would pull the HTTP client out
        // from under a delivery we just decided not to wait for, or out from under a diagnostic post
        // still in flight. shutdown() then refuses new work while letting what is already queued
        // finish -- unlike shutdownNow(), which would interrupt those posts and strand the futures of
        // anything it discarded.
        //
        // Queueing and shutting down are one step under submitLock, so that a flush cannot land in
        // between them and put a delivery behind the release, where it would find the sender closed.
        synchronized (submitLock) {
            shuttingDown = true;
            queueSenderRelease(scheduler);
            queueSenderRelease(diagnosticExecutor);
            scheduler.shutdown();
            diagnosticExecutor.shutdown();
        }
    }

    /** Queues the release on one of the posting threads, or runs it here if that thread has gone. */
    private void queueSenderRelease(ExecutorService executor) {
        try {
            executor.submit(guarded(this::releaseSenderWhenLast));
        } catch (RuntimeException e) { // the executor was shut down under us
            releaseSenderWhenLast(); // so nothing can still be posting on it
        }
    }

    /** Closes the sender once both of the threads that post through it have got this far. */
    private void releaseSenderWhenLast() {
        if (sendersStillDraining.decrementAndGet() > 0) {
            return;
        }
        try {
            eventSender.close();
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
        List<Event> run;
        List<EventSummarizer.EventSummary> summaries;
        synchronized (recordLock) {
            run = pending.isEmpty() ? Collections.<Event>emptyList() : new ArrayList<>(pending);
            pending.clear();
            summaries = buffer.takeSummaries();
        }
        OutboundEventBuffer.Payload payload;
        try {
            payload = buffer.encode(run, summaries);
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

    /**
     * Posts an event that has already been built. Requires the caller to have checked
     * {@link #diagnosticsSuspended()}, because for the periodic event building it is what consumes
     * the period.
     */
    private void sendDiagnosticEvent(DiagnosticEvent diagnosticEvent, boolean isInit) {
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

    /**
     * Must be called holding {@code stateLock}. Once closed, this only ever cancels: close() sets the
     * flag and then calls this under the same lock, so a call that got here first has its tasks
     * cancelled by close(), and any call after it finds the flag set.
     */
    private void updateScheduledTasks(boolean inBackground, boolean offline) {
        boolean stopped = closed.get();
        // Flushing stays scheduled whether or not we are offline or in the background; a run while
        // offline returns without doing anything. Cancelling it for an outage would restart the
        // interval on every reconnect, and a run of brief outages would then hold events back for
        // far longer than one interval. Left running, what an outage buffered goes out at the first
        // run after it ends.
        flushTask = enableOrDisableTask(!stopped, flushTask, flushIntervalMillis,
                this::deliverPayload);
        boolean diagnosticsEnabled = !stopped && diagnosticStore != null && !offline && !inBackground;
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
