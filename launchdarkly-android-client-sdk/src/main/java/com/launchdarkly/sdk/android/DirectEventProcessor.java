package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventSender;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 * What the application records is accepted, held and committed to the store by {@link EventCommitter}, which
 * says which calls are commit points and which thread each commit runs on. This class delivers what is
 * committed, and runs the processor's life: flushes, going offline and into the background, and close.
 * <p>
 * Threads. Every delivery runs on {@link #scheduler}, a single thread, queued there by {@link EventDeliveryScheduler}:
 * a requested one, the periodic one, the retry after a failure, and the final one {@code close()} queues. So no two deliveries of this processor overlap, and the final one
 * waits behind a post or retry already running there, within the budget {@code close()} gives it.
 * Diagnostic events go out through {@link DiagnosticReporter}, on a thread of their own.
 * <p>
 * Overlap is across processes. Two processes of one application read the same directory, so both can send
 * one stored batch. They send it under the same payload ID, and LaunchDarkly discards the repeat rather than
 * counting the events twice.
 * <p>
 * Flag values come first. Where events persist, what a previous run left waits at launch until a fixed time
 * after the SDK first goes online, and the deliveries this processor starts on its own send one batch at a time,
 * a gap apart, leaving room for flag requests. Both go by time alone: nothing here hears what the flag data
 * source is doing. A delivery the application asks for is never held.
 */
final class DirectEventProcessor implements EventProcessor {
    /** How long the deliveries this processor starts on its own wait, so that flag values come first. */
    static final class Pacing {
        static final Pacing SPECIFIED = new Pacing(2_000, 1_000);
        /** No waits at all, for tests about something else. */
        static final Pacing NONE = new Pacing(0, 0);

        /**
         * How long after the SDK first goes online that a previous run's events, and the diagnostic init event,
         * wait. It gives the application's startup traffic, the first flag request among it, the network first,
         * and is short enough for an application that dies a few seconds after every launch to still send them.
         */
        final long launchDelayMillis;
        /** The least time between one paced batch's answer and the next batch's request. */
        final long gapMillis;

        Pacing(long launchDelayMillis, long gapMillis) {
            this.launchDelayMillis = launchDelayMillis;
            this.gapMillis = gapMillis;
        }
    }

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
    private final EventCommitter committer;
    private final AnalyticsEventSender analyticsEventSender;
    /** When the next post may go, after a failure. Consulted before every post, and only there. */
    private final DeliveryRetryState retryState;
    private final URI eventsUri;
    private final DiagnosticReporter diagnostics;
    private final long flushIntervalMillis;
    private final long closeBudgetMillis;
    private final ScheduledExecutorService scheduler;
    private final LDLogger logger;

    // Guarded by stateLock.

    /**
     * Guards {@link #flushTask} and every {@link DiagnosticReporter#setMode}, and every change to
     * {@link #inBackground} and {@link #offline}, so the tasks are rescheduled in the order the changes
     * happen. Those two are atomic so they can be read without it.
     */
    private final Object stateLock = new Object();
    private ScheduledFuture<?> flushTask;
    private final AtomicBoolean inBackground;
    private final AtomicBoolean offline;

    /** Queues deliveries on {@link #scheduler}, holds the one retry, and decides when that thread stops. */
    private final EventDeliveryScheduler deliveryScheduler;

    // Atomic or volatile, so read and written without a lock.

    private final AtomicBoolean closed = new AtomicBoolean(false);
    /**
     * Whether a previous run of the application left events behind.
     * <p>
     * Those events are already as old as whatever happened to that run, so they are sent once the launch
     * gate opens rather than at the next interval. The application in front of the user has moved on; an
     * error report about a crash is not worth much thirty seconds late, and the process might not live that
     * long either.
     */
    private final AtomicBoolean hasEventsFromPreviousRun = new AtomicBoolean(false);

    private final Pacing pacing;

    /**
     * Whether events give way to flag values: only where the application asked for persistence, like the
     * rest of what persistence brings. Without it there is no previous run to hold back, and delivery is
     * what it was before persistence: no launch gate and no pacing.
     */
    private final boolean givesWayToFlags;

    /**
     * Whether the launch gate has opened. It opens once the launch delay has passed since the SDK first went
     * online. Until then neither a previous run's events nor the diagnostic init event are sent. Open from the
     * start where events do not give way. Only set on the scheduler thread.
     */
    private volatile boolean launchGateOpen;

    /** Set once, under {@link #stateLock}, when the SDK first goes online; the gate counts from then. */
    private volatile boolean wentOnline;
    private volatile long wentOnlineNanos;

    /** Events dropped for capacity, and evaluations the summarizer turned away, which diagnostics carry. */
    private final AtomicLong droppedEvents = new AtomicLong(0);

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
    // and stops at a kept failure, so the next post is that batch's unless capacity dropped it. Any response
    // other than a refusal ends the run; a post that got no response leaves it. Held only in memory, so a new
    // run of the application counts again.
    private final Object badRequestLock = new Object();
    private String badRequestPayloadId;
    private int badRequestsInARow;

    /**
     * How many failed reads in a row drop a batch. A read that fails is mostly one that will go on failing,
     * a permission taken away or a directory named like a batch, and expiry cannot delete a file it cannot
     * open, so the batch would be read, and fail, at every delivery for good. More than one, so a passing
     * failure such as running out of file descriptors costs nothing; a failed read queues no retry of its own,
     * so the reads are usually deliveries apart.
     */
    static final int READ_FAILURES_BEFORE_DROPPING = 3;

    /**
     * Failed reads in a row, by payload ID, of the batches whose last read failed. Unlike refusals, these
     * are counted for several batches at once, since a batch whose read failed is passed by. Held only in
     * memory, so a new run of the application counts again. Guarded by itself.
     */
    private final Map<String, Integer> readFailuresInARow = new HashMap<>();

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
            Pacing pacing,
            boolean initiallyInBackground,
            boolean initiallyOffline,
            ScheduledExecutorService scheduler,
            ExecutorService commitExecutor,
            ExecutorService diagnosticExecutor,
            LDLogger logger
    ) {
        this.store = store;
        this.analyticsEventSender = analyticsEventSender;
        this.retryState = retryState;
        this.eventsUri = eventsUri;
        this.flushIntervalMillis = flushIntervalMillis;
        this.closeBudgetMillis = closeBudgetMillis;
        this.pacing = pacing;
        this.givesWayToFlags = store.isPersisting();
        this.launchGateOpen = !givesWayToFlags;
        this.scheduler = scheduler;
        this.logger = logger;
        this.committer = new EventCommitter(eventBuffer, store, capacity, commitOnCallerThread, commitExecutor,
                closed, lostEvents, droppedEvents, logger);
        this.inBackground = new AtomicBoolean(initiallyInBackground);
        this.offline = new AtomicBoolean(initiallyOffline);
        this.deliveryScheduler = new EventDeliveryScheduler(scheduler, givesWayToFlags, pacing.gapMillis,
                () -> closed.get() || offline.get(), logger);
        this.diagnostics = new DiagnosticReporter(diagnosticStore, diagnosticEventSender, eventsUri,
                diagnosticRecordingIntervalMillis, scheduler, diagnosticExecutor,
                new DiagnosticReporter.Processor() {
                    @Override
                    public long getAndClearDroppedCount() {
                        return DirectEventProcessor.this.getAndClearDroppedCount();
                    }

                    @Override
                    public void recordPastTime(long timeFromServer) {
                        committer.recordPastTime(timeFromServer);
                    }
                },
                logger);

        // Off the calling thread: this is on the path of LDClient.init, and it reads the filesystem. Only where
        // events persist, since memory holds nothing from a previous run.
        if (store.isPersisting()) {
            deliveryScheduler.submit(this::recoverEventsFromPreviousRun);
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
        committer.recordEvaluation(context, flagKey, flagVersion, variation, value, reason, defaultValue,
                requireFullEvent, debugEventsUntilDate);
    }

    @Override
    public void recordIdentifyEvent(LDContext context) {
        if (isStopped() || context == null) {
            return;
        }
        committer.recordAtCommitPoint(new Event.Identify(System.currentTimeMillis(), context));
    }

    @Override
    public void recordCustomEvent(LDContext context, String eventKey, LDValue data, Double metricValue) {
        if (isStopped() || context == null) {
            return;
        }
        committer.recordAtCommitPoint(new Event.Custom(System.currentTimeMillis(), eventKey, context, data,
                metricValue));
    }

    /** As {@link EventCommitter#record}. */
    long record(Event event) {
        return committer.record(event);
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
        deliveryScheduler.wakePacedDelivery();
    }

    @Override
    public void flush() {
        if (isStopped()) {
            return;
        }
        committer.commitBeforeDelivery();
        queueDelivery(false, false);
    }

    @Override
    public void blockingFlush() {
        if (isStopped()) {
            return;
        }
        committer.commitBeforeDelivery();
        Future<Boolean> delivery = queueDelivery(false, false);
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
        committer.queueCommitBeforeDelivery();
        return queueDelivery(true, false);
    }

    /**
     * {@link #flushAsync()} that bypasses the backoff: it posts whatever wait the backoff is in, as close()
     * does. For a caller about to lose the ability to send the events, such as an uncaught exception
     * handler: {@code LDClient.flushAndWait}, and {@code LDCrashHandler} through it. After a refusal that wait
     * is minutes to an hour, and where nothing persists the events would not outlive it. A failure it meets
     * there leaves the wait as it was. The caller's timeout bounds how long it waits for the answer.
     */
    Future<Boolean> flushBypassingBackoff() {
        if (isStopped()) {
            return new LDSuccessFuture<>(false);
        }
        committer.queueCommitBeforeDelivery();
        return queueDelivery(true, true);
    }

    /**
     * Queues a delivery, or joins one that is already queued and has not started; see
     * {@link EventDeliveryScheduler#queue}.
     *
     * @param answersACaller true if the caller will hear the outcome, so that the delivery reports
     *   any events lost up to the batch it closes, and false if the caller discards it
     * @param bypassBackoff true if the delivery is to post whatever wait the backoff is in
     */
    private Future<Boolean> queueDelivery(boolean answersACaller, boolean bypassBackoff) {
        return deliveryScheduler.queue(answersACaller, bypassBackoff, this::runDelivery);
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
    private void runDelivery(LDAwaitFuture<Boolean> result, EventDeliveryScheduler.Joined joined) {
        DeliveryOutcome outcome;
        try {
            outcome = commitAndDeliverReportingOutcome(deliveryScheduler.requestedRun(joined.bypassesBackoff));
        } catch (Throwable t) {
            // Caught here rather than left to guarded(), because a caller is waiting on the future
            // and completing it matters more than the stack reaching the executor.
            logUnexpectedError(t);
            outcome = DeliveryOutcome.CLOSED_NOTHING;
        }
        // Claimed before the next delivery can run, however long this one's retry takes to answer.
        boolean lostEvents = joined.answersACaller && claimEventsLost(outcome.lostEventsMark);
        if (outcome.retry == null) {
            answer(result, outcome.delivered, lostEvents);
        } else {
            completeWhenRetryEnds(result, outcome, lostEvents);
        }
    }

    /**
     * Answers the waiters when the queued retry does, rather than telling them a batch failed when
     * it is about to be sent again. They are answered as the retry ends, on the scheduler thread: a
     * thread blocked on it for each delivery that took it as theirs would pile up as fast as flushes
     * are called.
     */
    private void completeWhenRetryEnds(LDAwaitFuture<Boolean> result, DeliveryOutcome outcome,
                                       boolean lostEvents) {
        LDAwaitFuture<Boolean> retried = outcome.retry;
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
        deliveryScheduler.wakePacedDelivery();
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
        Future<?> finalCommit = store.isPersisting()
                ? committer.commitWithinBudget(deadlineNanos, closeBudgetMillis) : null;

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
        Callable<DeliveryOutcome> finalDelivery =
                () -> deliverPayloadAndRetryLater(deliveryScheduler.closingRun());
        Future<DeliveryOutcome> delivery = deliveryScheduler.submit(finalDelivery);
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
        // whatever is still in flight there: a delivery, or a diagnostic post, that we just decided not to
        // wait for. The store goes only once the final commit is through with it, which may be after the
        // budget if the disk stalled.
        deliveryScheduler.shutDown(finalCommit, this::releaseDeliveryResources);
        diagnostics.close();
        committer.shutdown();
    }

    /** What the delivery thread owns: the store it drains and the sender it posts batches through. */
    private void releaseDeliveryResources() {
        store.close();
        analyticsEventSender.close();
    }

    /**
     * Serializes and sends everything buffered, for the periodic flush and for what a previous run left,
     * which have nobody waiting to find out how it went. The periodic one is a fixed-delay series, so a run
     * is only ever scheduled once the one before it has finished and these cannot pile up the way requested
     * flushes could.
     * <p>
     * Paced where events give way to flags, since nobody asked for it now: see {@link EventDeliveryRun#awaitTurn()}.
     */
    private void deliverPayload() {
        commitAndDeliverReportingOutcome(deliveryScheduler.ownRun());
    }

    /**
     * The periodic flush, on the scheduler thread. Until the launch gate opens it only commits, where events persist:
     * a delivery sends the oldest batches first, which are what a previous run left, so one now would send them ahead
     * of the gate. What this run recorded goes with the first delivery after the gate opens.
     */
    private void flushIntervalEnded() {
        if (!launchGateOpen) {
            if (store.isPersisting()) {
                committer.commitDurably();
            }
            return;
        }
        deliverPayload();
    }

    /**
     * Writes everything accepted so far, then delivers.
     * <p>
     * This commit looks redundant with the one in {@link EventCommitter#commitAndCloseBatch()}, and online it is: the
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
    private DeliveryOutcome commitAndDeliverReportingOutcome(EventDeliveryRun run) {
        if (store.isPersisting()) {
            committer.commitDurably();
        }
        return deliverPayloadAndRetryLater(run);
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
        final LDAwaitFuture<Boolean> retry;
        /**
         * The loss count as of the close of this delivery's batch, which is as far as it can answer
         * for; see {@link #claimEventsLost(long)}.
         */
        final long lostEventsMark;

        private DeliveryOutcome(boolean delivered, LDAwaitFuture<Boolean> retry, long lostEventsMark) {
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
    private DeliveryOutcome deliverPayloadAndRetryLater(EventDeliveryRun run) {
        if (offline.get()) {
            return DeliveryOutcome.CLOSED_NOTHING;
        }

        DeliveryPass pass = deliverWaitingThenNew(run);
        if (pass.outcome.kept()) {
            // A batch kept for a reason other than the network, such as a read that failed, has no wait of its
            // own, and is retried after the first one.
            long waitNanos = retryState.remainingWaitNanos();
            long delayNanos = waitNanos > 0 ? waitNanos
                    : TimeUnit.MILLISECONDS.toNanos(retryState.initialDelayMillis());
            LDAwaitFuture<Boolean> retry = deliveryScheduler.scheduleRetry(this::retryPendingBatches, delayNanos);
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
    private DeliveryPass deliverWaitingThenNew(EventDeliveryRun run) {
        Set<String> passedBy = new HashSet<>();
        BatchesOutcome waiting = deliverPendingBatches(run, passedBy);
        if (waiting.kept()) {
            return new DeliveryPass(waiting, committer.commitLeavingTheLogOpen());
        }
        EventCommitter.Close close = committer.commitAndCloseBatch();
        BatchesOutcome closed = deliverListedBatches(run, passedBy);
        if (closed.kept() || !close.closedEverything) {
            // Events left in a log that would not close are still on the disk, for the next close to try again
            // with; this pass cannot say they got out.
            return new DeliveryPass(closed.kept() ? closed : BatchesOutcome.RETRYABLE, close.lostEventsMark);
        }
        boolean delivered = waiting == BatchesOutcome.DELIVERED && closed == BatchesOutcome.DELIVERED;
        return new DeliveryPass(delivered ? BatchesOutcome.DELIVERED : BatchesOutcome.FAILED, close.lostEventsMark);
    }

    private enum BatchesOutcome {
        DELIVERED,
        FAILED,
        /** Kept after an attempt that failed in a way that may pass, or a listing that did. */
        RETRYABLE,
        /** Kept without an attempt, the backoff not allowing one yet. */
        HELD,
        /**
         * Kept after a read that failed, for a later delivery to read again, while the batches behind it are
         * still attempted. Only ever the outcome of one batch; a pass with one is {@link #FAILED}.
         */
        PASSED_BY;

        boolean kept() {
            return this == RETRYABLE || this == HELD;
        }
    }

    /**
     * Makes one attempt at every batch waiting, oldest first: the others are deliveries an earlier attempt
     * did not finish, or that a previous run of the application never got to start.
     * <p>
     * Stops at the first failure that may pass, other than a read. The batches behind it would most likely
     * meet the same network, and the retry takes all of them again.
     *
     * @param passedBy the batches whose read failed in this pass, which this adds to
     */
    private BatchesOutcome deliverPendingBatches(EventDeliveryRun run, Set<String> passedBy) {
        reportDiscarded(store.discardExpired());
        return deliverListedBatches(run, passedBy);
    }

    /**
     * {@link #deliverPendingBatches} without the discard, for a second pass moments after the first.
     * <p>
     * Where the directory could not be listed, delivers the batches the store last counted and answers
     * {@link BatchesOutcome#RETRYABLE}: a batch the listing could not see is still on the disk, and the
     * listing may work next time.
     *
     * @param passedBy the batches whose read failed earlier in this pass, which are not read again, and to
     *   which this adds any whose read fails now
     */
    private BatchesOutcome deliverListedBatches(EventDeliveryRun run, Set<String> passedBy) {
        EventStore.Listing listing = store.listPendingBatches();
        boolean allDelivered = true;
        for (EventStore.Batch batch : listing.batches) {
            if (passedBy.contains(batch.payloadId)) {
                // Read once a pass: read again moments later, it would most likely fail as it did, and count
                // as two failures in a row.
                allDelivered = false;
                continue;
            }
            run.awaitTurn();
            if (offline.get()) {
                return BatchesOutcome.FAILED;
            }
            BatchesOutcome outcome = deliver(batch, run);
            run.postEnded();
            if (outcome.kept()) {
                return outcome;
            }
            if (outcome == BatchesOutcome.PASSED_BY) {
                passedBy.add(batch.payloadId);
            }
            allDelivered &= outcome == BatchesOutcome.DELIVERED;
        }
        if (!listing.complete) {
            return BatchesOutcome.RETRYABLE;
        }
        return allDelivered ? BatchesOutcome.DELIVERED : BatchesOutcome.FAILED;
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
        if (deliveryScheduler.isShuttingDown()) {
            // close() has queued the release of the store and the sender behind this.
            return false;
        }
        if (offline.get()) {
            return false;
        }
        try {
            BatchesOutcome outcome = deliverWaitingThenNew(deliveryScheduler.requestedRun(false)).outcome;
            if (outcome == BatchesOutcome.HELD) {
                deliveryScheduler.scheduleRetryAgain(this::retryPendingBatches, retryState.remainingWaitNanos());
            }
            return outcome == BatchesOutcome.DELIVERED;
        } catch (RuntimeException e) {
            logUnexpectedError(e);
            return false;
        }
    }

    /**
     * @return DELIVERED if the batch arrived, or held nothing left to send; FAILED if events were lost,
     *   whether the batch could not be read by this version, or {@link #READ_FAILURES_BEFORE_DROPPING} times
     *   in a row, arrived short, or had its payload refused {@link #BAD_REQUESTS_BEFORE_DROPPING} times in a
     *   row; PASSED_BY if a read failed fewer times than that; RETRYABLE if it is kept to be sent again,
     *   which is every other failure to post it; HELD for a post the backoff did not allow
     */
    private BatchesOutcome deliver(EventStore.Batch batch, EventDeliveryRun run) {
        boolean duringAWait = retryState.remainingWaitNanos() > 0;
        if (duringAWait && !run.postsThroughTheBackoff()) {
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
            if (countReadFailure(batch) >= READ_FAILURES_BEFORE_DROPPING) {
                logger.error("Dropping {} stored event(s) that could not be read {} times in a row: {}",
                        batch.eventCount, READ_FAILURES_BEFORE_DROPPING, LogValues.exceptionSummary(e));
                store.remove(batch);
                reportBatchLost(batch);
                return BatchesOutcome.FAILED;
            }
            // Kept, since a read that failed may not fail next time, but passed by rather than stopped at.
            // Stopped at, it held back every batch behind it, the open log's included, on every pass for as
            // long as the read kept failing: for good where expiry cannot open the file to delete it either.
            logger.warn("Could not read stored events, will try again at the next delivery: {}",
                    LogValues.exceptionSummary(e));
            return BatchesOutcome.PASSED_BY;
        }
        endReadFailures(batch);
        if (payload == null) {
            // Already delivered by another process of this application, or holding no events. Either way
            // nothing is lost and there is nothing to keep.
            store.remove(batch);
            return BatchesOutcome.DELIVERED;
        }
        // Damage after the first frame: what came before it is sent, and the rest is gone with the file. Counted
        // once the batch is removed rather than now, since one kept for a retry is read, and found short, again.
        int leftOut = Math.max(0, batch.eventCount - payload.eventCount);
        diagnostics.recordEventsInBatch(payload.eventCount);
        store.startedSending(batch);
        try {
            AnalyticsEventSender.Result result = analyticsEventSender.sendBatch(payload, payload.eventCount,
                    batch.payloadId, eventsUri);
            if (!result.success) {
                if (duringAWait) {
                    retryState.recordFailureDuringWait(result.unexpected, result.retryAfterMillis);
                } else {
                    retryState.recordFailure(result.unexpected, result.retryAfterMillis);
                }
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
                committer.recordPastTime(result.serverTime.getTime());
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
     * @return how many reads in a row of this batch have failed, counting this one; once that reaches
     *   {@link #READ_FAILURES_BEFORE_DROPPING} the count is ended, since the batch is dropped
     */
    private int countReadFailure(EventStore.Batch batch) {
        synchronized (readFailuresInARow) {
            Integer previous = readFailuresInARow.get(batch.payloadId);
            int count = previous == null ? 1 : previous + 1;
            if (count >= READ_FAILURES_BEFORE_DROPPING) {
                readFailuresInARow.remove(batch.payloadId);
            } else {
                readFailuresInARow.put(batch.payloadId, count);
            }
            return count;
        }
    }

    /** Ends a batch's run of failed reads, at a read that worked. */
    private void endReadFailures(EventStore.Batch batch) {
        synchronized (readFailuresInARow) {
            readFailuresInARow.remove(batch.payloadId);
        }
    }

    /**
     * @param status the HTTP status, or 0 where no response arrived
     * @return how many refusals of the payload in a row this batch has had, counting this response; zero
     *   for any other status, which ends the run, and once the batch is dropped
     */
    private int countBadRequest(EventStore.Batch batch, int status) {
        synchronized (badRequestLock) {
            if (status == 0) {
                // A network or TLS failure leaves the run as it was. The service did not answer, so it said nothing
                // about the payload: had it ended the run, refusals with network failures between them would never
                // reach two, and a batch the service will never take would stay the oldest, holding back every
                // batch behind it for good where nothing expires it, as in memory.
                return badRequestPayloadId != null && badRequestPayloadId.equals(batch.payloadId)
                        ? badRequestsInARow : 0;
            }
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
        flushTask = EventUtil.enableOrDisableTask(scheduler, !stopped, flushTask, flushIntervalMillis,
                guarded(this::flushIntervalEnded));
        if (!stopped && !offline && !wentOnline) {
            wentOnlineNanos = System.nanoTime();
            wentOnline = true;
            if (!launchGateOpen) {
                scheduleLaunchGateCheck(TimeUnit.MILLISECONDS.toNanos(pacing.launchDelayMillis));
            }
        }
        // Events a previous run left on disk have already waited out a whole process, so they do not
        // wait out an interval as well, only the launch gate. An application that dies within one interval
        // of every start would otherwise never deliver them.
        if (!stopped && !offline && launchGateOpen && hasEventsFromPreviousRun.compareAndSet(true, false)) {
            deliveryScheduler.submit(this::deliverPayload);
        }
        diagnostics.setMode(!stopped && !offline && !inBackground, launchGateOpen);
    }

    /**
     * Opens the launch gate once the launch delay has passed since the SDK first went online, and otherwise
     * looks again then. On the scheduler thread.
     */
    private void checkLaunchGate() {
        if (launchGateOpen || isStopped()) {
            return;
        }
        long remaining = TimeUnit.MILLISECONDS.toNanos(pacing.launchDelayMillis) - (System.nanoTime() - wentOnlineNanos);
        if (remaining > 0) {
            scheduleLaunchGateCheck(remaining);
            return;
        }
        launchGateOpen = true;
        if (hasEventsFromPreviousRun.get()) {
            logger.debug("Sending events from a previous run, {}ms after going online", pacing.launchDelayMillis);
        }
        synchronized (stateLock) {
            updateScheduledTasks(inBackground.get(), offline.get());
        }
    }

    private void scheduleLaunchGateCheck(long delayNanos) {
        deliveryScheduler.schedule(this::checkLaunchGate, delayNanos);
    }

    /** As {@link EventUtil#guarded}. */
    private Runnable guarded(Runnable task) {
        return EventUtil.guarded(logger, task);
    }

    private boolean isStopped() {
        return closed.get();
    }

    private void logUnexpectedError(Throwable e) {
        EventUtil.logUnexpectedError(logger, e);
    }
}
