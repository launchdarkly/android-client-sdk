package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Puts the event processor's deliveries on its scheduler thread, one at a time, and decides when that thread
 * stops taking them.
 * <p>
 * Every delivery runs there: a requested one, the periodic one, the retry after a failure, and the final one
 * {@code close()} queues. So no two overlap, and each starts only once the one before it ends. This holds what
 * that needs across threads: the requested delivery that is queued and has not started, the one retry, and the
 * handover from running to shut down.
 */
final class EventDeliveryScheduler {
    /** A requested delivery, given the future its callers wait on and whether one of them hears the outcome. */
    interface RequestedDelivery {
        void run(LDAwaitFuture<Boolean> result, boolean answersACaller);
    }

    private final ScheduledExecutorService scheduler;
    private final boolean pacesDeliveries;
    private final long gapMillis;
    private final DeliveryRun.PacingEnd pacingEnd;
    private final LDLogger logger;

    // Guarded by submitLock.

    /**
     * Guards the handover from running to shut down: held for the length of a submit, and by
     * {@link #shutDown} while it queues the release of the thread's resources and stops the
     * executor accepting work. Nothing blocking happens under it.
     */
    private final Object submitLock = new Object();

    /** Set under {@link #submitLock} once {@link #shutDown} has queued the release of those resources. */
    private boolean shuttingDown = false;

    /**
     * The retry queued after a delivery failed in a way that may pass. One at a time: it retries every batch
     * still waiting, so a second would only repeat it.
     */
    private ScheduledFuture<Boolean> pendingRetry;

    // Guarded by flushLock.

    /**
     * Guards {@link #pendingFlush} and {@link #pendingFlushAnswersACaller}. Taken on a caller's thread and
     * on the delivery thread, never while holding the committer's record lock, and nothing blocking happens
     * under it.
     */
    private final Object flushLock = new Object();

    /**
     * The delivery that is queued but has not started, which a flush request arriving now can wait
     * on instead of queuing another. Null while nothing is queued, and cleared again as the queued
     * delivery begins, which is the point past which it can no longer speak for what is recorded.
     */
    private LDAwaitFuture<Boolean> pendingFlush;

    /**
     * Whether a caller who is there to hear the outcome is waiting on {@link #pendingFlush}, as opposed to only
     * callers that discard it.
     */
    private boolean pendingFlushAnswersACaller;

    /**
     * Whether a delivery someone asked for is queued, which a paced delivery holding the scheduler stops
     * pacing for. Mirrors {@code pendingFlush != null}, written under {@link #flushLock}.
     */
    private volatile boolean requestedDeliveryQueued;

    /** What a paced delivery waits on for its gap, so that a flush, going offline or close can end the wait. */
    private final Object pacingLock = new Object();

    /**
     * @param pacesDeliveries whether the deliveries the processor starts on its own are paced at all
     * @param gapMillis the least time between one paced batch's answer and the next batch's request
     * @param pacingEnd what else, besides a requested delivery queuing, ends a paced delivery's wait
     */
    EventDeliveryScheduler(ScheduledExecutorService scheduler, boolean pacesDeliveries, long gapMillis,
                      DeliveryRun.PacingEnd pacingEnd, LDLogger logger) {
        this.scheduler = scheduler;
        this.pacesDeliveries = pacesDeliveries;
        this.gapMillis = gapMillis;
        this.pacingEnd = pacingEnd;
        this.logger = logger;
    }

    /**
     * Starts a run of the processor's own, which is paced where deliveries are, since nobody asked for it now.
     * On the scheduler thread.
     */
    DeliveryRun ownRun() {
        return newRun(false, pacesDeliveries);
    }

    /** Starts a run someone asked for, or a retry, which goes out back to back. On the scheduler thread. */
    DeliveryRun requestedRun() {
        return newRun(false, false);
    }

    /** Starts close()'s final run, which posts whatever wait the backoff is in. On the scheduler thread. */
    DeliveryRun closingRun() {
        return newRun(true, false);
    }

    private DeliveryRun newRun(boolean throughTheBackoff, boolean paced) {
        return new DeliveryRun(throughTheBackoff, paced, gapMillis, pacingLock,
                () -> requestedDeliveryQueued || pacingEnd.reached());
    }

    /**
     * Queues a requested delivery, or hands back one that is already queued and has not started.
     * <p>
     * A delivery that has not started yet will send everything recorded before it starts. That
     * includes the caller's events, so waiting on it is as good as queuing a new one. Without this,
     * flushes arriving faster than a post completes each queue their own, and the one that matters --
     * the {@code flushAndWait} at shutdown -- waits behind all of them.
     *
     * @param answersACaller true if the caller will hear the outcome, and false if the caller discards it
     * @return what the delivery answers, or false at once if it is shutting down and nothing will be sent
     */
    Future<Boolean> queue(boolean answersACaller, RequestedDelivery delivery) {
        LDAwaitFuture<Boolean> result;
        synchronized (flushLock) {
            if (pendingFlush != null) {
                pendingFlushAnswersACaller |= answersACaller;
                return pendingFlush;
            }
            LDAwaitFuture<Boolean> queued = new LDAwaitFuture<>();
            if (submit(() -> delivery.run(queued, begin(queued))) == null) {
                return new LDSuccessFuture<>(false);
            }
            result = queued;
            pendingFlush = result;
            pendingFlushAnswersACaller = answersACaller;
            requestedDeliveryQueued = true;
        }
        // A paced delivery holding the scheduler sends the rest back to back, so this one is not held behind it.
        wakePacedDelivery();
        return result;
    }

    /**
     * Takes a queued delivery off the queue as it begins, so that requests arriving from then on need a
     * delivery of their own: this one is about to take the buffer, and what it takes is all it can speak for.
     *
     * @return whether a caller who hears the outcome joined it
     */
    private boolean begin(LDAwaitFuture<Boolean> result) {
        synchronized (flushLock) {
            if (pendingFlush != result) {
                return false;
            }
            pendingFlush = null;
            requestedDeliveryQueued = false;
            boolean answersACaller = pendingFlushAnswersACaller;
            pendingFlushAnswersACaller = false;
            return answersACaller;
        }
    }

    /** Has a paced delivery waiting for its turn look again at whether it should stop waiting. */
    void wakePacedDelivery() {
        if (pacesDeliveries) {
            synchronized (pacingLock) {
                pacingLock.notifyAll();
            }
        }
    }

    /**
     * Queues the retry on the scheduler rather than sleeping for it there, which would hold every flush,
     * and close()'s final delivery, behind the wait.
     *
     * @param delayNanos how long until the backoff allows the next post
     * @return the retry, a retry already queued, or null if shutting down
     */
    Future<Boolean> scheduleRetry(Callable<Boolean> retry, long delayNanos) {
        synchronized (submitLock) {
            if (shuttingDown) {
                return null;
            }
            if (pendingRetry != null && !pendingRetry.isDone()) {
                return pendingRetry;
            }
            try {
                pendingRetry = scheduler.schedule(retry, delayNanos, TimeUnit.NANOSECONDS);
            } catch (RuntimeException e) { // the executor was shut down under us
                return null;
            }
            logger.warn("Will retry posting events in {}ms after a failure",
                    TimeUnit.NANOSECONDS.toMillis(delayNanos));
            return pendingRetry;
        }
    }

    /** As {@link #scheduleRetry}, from the retry that is running, so that it does not stand in for the next. */
    Future<Boolean> scheduleRetryAgain(Callable<Boolean> retry, long delayNanos) {
        synchronized (submitLock) {
            pendingRetry = null;
            return scheduleRetry(retry, delayNanos);
        }
    }

    /** Runs a task on the scheduler thread after a delay, unless shutting down. */
    void schedule(Runnable task, long delayNanos) {
        synchronized (submitLock) {
            if (shuttingDown) {
                return;
            }
            try {
                scheduler.schedule(guarded(task), delayNanos, TimeUnit.NANOSECONDS);
            } catch (RuntimeException e) { // the executor was shut down under us
            }
        }
    }

    /**
     * Puts work on the delivery thread, and is the one place that decides whether there is still a
     * thread willing to take it. The {@code isStopped} tests the processor makes first are a fast path
     * that saves the work of getting here, not a guarantee; this is what a delivery is actually
     * ordered against close().
     *
     * @return the submitted task, or null if shutting down or already shut down
     */
    Future<?> submit(Runnable task) {
        synchronized (submitLock) {
            if (shuttingDown) {
                // The release of the sender is already queued. Work accepted now would be behind it in the
                // queue and would run against a sender that had been closed.
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
     * for. Not wrapped in {@link EventUtil#guarded}, because here the caller is there to receive what
     * escapes.
     *
     * @return the submitted task, or null if shutting down or already shut down
     */
    <T> Future<T> submit(Callable<T> task) {
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

    /** Whether {@link #shutDown} has queued the release of the thread's resources. */
    boolean isShuttingDown() {
        synchronized (submitLock) {
            return shuttingDown;
        }
    }

    /**
     * Stops taking work, cancels the retry, and queues the release of what the delivery thread owns behind
     * whatever is still in flight there. Closing it inline would pull it out from under a delivery that close()
     * decided not to wait for. {@code shutdown()} then refuses new work while letting what is already queued
     * finish -- unlike {@code shutdownNow()}, which would interrupt those and strand the futures of anything
     * it discarded.
     * <p>
     * Queueing and shutting down are one step under {@link #submitLock}, so that a flush cannot land in
     * between them and put a delivery behind the release, where it would find the store closed.
     *
     * @param awaitFirst work still using those resources, such as the final commit, which the release waits
     *   for on the scheduler thread, never the caller's, however long a stalled disk makes it; may be null
     * @param release run on the scheduler thread, or at once if that thread is already gone
     */
    void shutDown(Future<?> awaitFirst, Runnable release) {
        synchronized (submitLock) {
            shuttingDown = true;
            if (pendingRetry != null) {
                // Not interrupted if it is already posting: it checks isShuttingDown before it starts, and
                // one that got past that runs ahead of the release queued below on the same thread.
                pendingRetry.cancel(false);
                pendingRetry = null;
            }
            try {
                scheduler.submit(guarded(() -> {
                    awaitQuietly(awaitFirst);
                    release.run();
                }));
            } catch (RuntimeException e) { // the executor was shut down under us
                release.run();
            }
            scheduler.shutdown();
        }
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

    private Runnable guarded(Runnable task) {
        return EventUtil.guarded(logger, task);
    }
}
