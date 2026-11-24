package com.launchdarkly.sdk.android;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * When event delivery may post again after a failure, following the SDK Retry Behavior specification as
 * event durability §9 binds it.
 * <p>
 * A failure never stops delivery, it only makes the next post wait. There are two kinds of wait:
 * <ul>
 *   <li>After an ordinary failure, such as no network or a 5xx without {@code Retry-After}, the wait grows
 *   from a second up to the flush interval, so it is never later than the schedule already would be. Going
 *   online again ends it.</li>
 *   <li>The service sets the wait with a {@code Retry-After}, or with a refusal likely to last, such as a
 *   401 or 403, which waits about eight and a half minutes, doubling up to an hour, rather than deleting the
 *   batch: a valid key is sometimes refused for minutes during an incident, and every event the fleet
 *   recorded meanwhile would be lost. Once refused, every wait until a delivery is accepted is of this kind.
 *   Going online again keeps it.</li>
 * </ul>
 * One accepted delivery puts everything back.
 * <p>
 * One per event processor, so each environment backs off on its own. Held only in memory, so a new run of
 * the application posts at once.
 */
final class DeliveryRetryState {
    /** The longest a {@code Retry-After} may make delivery wait, so a bad value cannot look like stopping. */
    static final long MAX_SERVER_DIRECTED_WAIT_MILLIS = TimeUnit.HOURS.toMillis(1);

    /** The ceiling once a refusal has been seen, until a delivery is accepted. */
    static final long EXTENDED_CEILING_MILLIS = TimeUnit.HOURS.toMillis(1);

    /**
     * Where the attempt count goes at least to on a refusal, so its first wait is 2^9 initial delays, about
     * eight and a half minutes, rather than starting from a second.
     */
    static final int ATTEMPTS_AFTER_UNEXPECTED = 10;

    private final long initialDelayMillis;
    private final long normalCeilingMillis;
    private final long extendedCeilingMillis;
    private final Random random;

    // Guarded by this.
    private int attempts;
    private long maxDelayMillis;
    private boolean waiting;
    private long nextAttemptNanos;
    // A refusal since the last accepted delivery, which keeps the extended ceiling.
    private boolean refused;
    // The wait is a Retry-After's. A refusal's is marked by refused.
    private boolean serverDirectedWait;

    /**
     * @param flushIntervalMillis the normal ceiling; never below the initial delay, so a short interval does
     *   not make a failing service be posted to more often than once a second
     */
    DeliveryRetryState(long flushIntervalMillis) {
        this(DirectEventProcessor.RETRY_DELAY_MILLIS, Math.max(flushIntervalMillis,
                DirectEventProcessor.RETRY_DELAY_MILLIS), EXTENDED_CEILING_MILLIS, new Random());
    }

    DeliveryRetryState(long initialDelayMillis, long normalCeilingMillis, long extendedCeilingMillis,
                       Random random) {
        this.initialDelayMillis = initialDelayMillis;
        this.normalCeilingMillis = normalCeilingMillis;
        this.extendedCeilingMillis = extendedCeilingMillis;
        this.random = random;
        this.maxDelayMillis = normalCeilingMillis;
    }

    /** The wait after a first failure, which a flush waiting on the delivery that failed sits out. */
    long initialDelayMillis() {
        return initialDelayMillis;
    }

    /** An accepted delivery, which is all it takes for the next failure to start from a second again. */
    synchronized void recordSuccess() {
        attempts = 0;
        maxDelayMillis = normalCeilingMillis;
        waiting = false;
        refused = false;
        serverDirectedWait = false;
    }

    /**
     * Going online again:
     * <ul>
     *   <li>ends the wait after an ordinary failure, which is most often the network that just came
     *   back.</li>
     *   <li>keeps a wait the service set, either a {@code Retry-After} or the longer backoff after a refusal,
     *   as the network coming back does not change the service's answer.</li>
     * </ul>
     * The attempt count stays either way, as only an accepted delivery puts it back.
     *
     * @return true when a post has failed since the last accepted one and no wait holds the next back any
     *   more, so a delivery may go now rather than at the next flush interval
     */
    synchronized boolean endOrdinaryWait() {
        if (attempts == 0) {
            return false;
        }
        if (remainingWaitNanos() > 0 && (refused || serverDirectedWait)) {
            return false;
        }
        waiting = false;
        return true;
    }

    /**
     * @param unexpected whether the failure is unlikely to pass soon: a 4xx other than 400, 408, 413, 415,
     *   422 and 429, or
     *   a TLS or certificate failure
     * @param retryAfterMillis the wait the response asked for, or a negative number where it gave none
     * @return how long nothing may be posted
     */
    synchronized long recordFailure(boolean unexpected, long retryAfterMillis) {
        if (unexpected) {
            attempts = Math.max(attempts + 1, ATTEMPTS_AFTER_UNEXPECTED);
            maxDelayMillis = extendedCeilingMillis;
            refused = true;
        } else {
            attempts++;
        }
        serverDirectedWait = retryAfterMillis >= 0;
        long waitMillis;
        if (retryAfterMillis >= 0) {
            // Every instance hears the same value at the same moment, so it is jittered to keep them from
            // posting together when it runs out.
            long directed = Math.min(retryAfterMillis, MAX_SERVER_DIRECTED_WAIT_MILLIS);
            waitMillis = directed - (long) (random.nextDouble() * (directed / 2.0));
        } else {
            // Not jittered: each instance's failures follow its own history, which already spreads them.
            int doublings = Math.min(attempts - 1, 40);
            waitMillis = Math.min(maxDelayMillis, initialDelayMillis << doublings);
        }
        waiting = true;
        nextAttemptNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        return waitMillis;
    }

    /**
     * A failure of a post that went through a wait, as close() and {@code flushAndWait} may: an extra try,
     * not a step of the backoff. It leaves the attempt count and the ceiling as they were, so a caller
     * flushing through a refusal several times does not push flush, the periodic delivery and the retry out
     * to the hour. A {@code Retry-After} can still make the wait longer, never shorter. Once the wait has run
     * out it is the next step of the backoff.
     *
     * @param unexpected as {@link #recordFailure}
     * @param retryAfterMillis as {@link #recordFailure}
     */
    synchronized void recordFailureDuringWait(boolean unexpected, long retryAfterMillis) {
        if (remainingWaitNanos() == 0) {
            recordFailure(unexpected, retryAfterMillis);
            return;
        }
        if (retryAfterMillis < 0) {
            return;
        }
        long directed = Math.min(retryAfterMillis, MAX_SERVER_DIRECTED_WAIT_MILLIS);
        long waitMillis = directed - (long) (random.nextDouble() * (directed / 2.0));
        long asked = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        if (asked - nextAttemptNanos > 0) {
            nextAttemptNanos = asked;
            serverDirectedWait = true;
        }
    }

    /** How long until a post is allowed, in nanoseconds; zero once it is. */
    synchronized long remainingWaitNanos() {
        if (!waiting) {
            return 0;
        }
        long remaining = nextAttemptNanos - System.nanoTime();
        if (remaining <= 0) {
            waiting = false;
            return 0;
        }
        return remaining;
    }
}
