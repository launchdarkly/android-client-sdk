package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * Computes the wait before a polling component polls again. The backoff clears after enough
 * consecutive successful polls.
 * <p>
 * This class is not thread-safe. The owning component must serialize access to it.
 */
final class PollingRetryState {
    /**
     * The backoff clears after this many consecutive successful polls.
     */
    static final int RESET_THRESHOLD_SUCCESSES = 2;

    private final long pollIntervalMillis;
    private final RetryRegime extended;
    private final Random random;

    private boolean inExtendedRegime = false;
    private int attempts = 0;
    private int consecutiveSuccesses = 0;

    /**
     * @param pollIntervalMillis the configured poll interval, which must be positive
     */
    PollingRetryState(long pollIntervalMillis) {
        this(pollIntervalMillis, RetryRegime.EXTENDED, new Random());
    }

    /**
     * Creates a retry state with an explicit extended regime.
     *
     * @param pollIntervalMillis the configured poll interval, which must be positive
     * @param extended           the regime entered by an {@code unexpected} failure, raised to the
     *                           poll interval where smaller
     * @param random             source of jitter
     */
    PollingRetryState(
            long pollIntervalMillis,
            @NonNull RetryRegime extended,
            @NonNull Random random
    ) {
        this.pollIntervalMillis = Math.max(1, pollIntervalMillis);
        this.extended = extended.atLeast(this.pollIntervalMillis);
        this.random = random;
    }

    /**
     * Records a successful poll. The backoff clears after {@link #RESET_THRESHOLD_SUCCESSES}
     * consecutive successes.
     */
    void recordSuccess() {
        consecutiveSuccesses++;
        if (consecutiveSuccesses >= RESET_THRESHOLD_SUCCESSES) {
            inExtendedRegime = false;
            attempts = 0;
        }
    }

    /**
     * Records a failed poll. Call this before {@link #nextDelayMillis()}.
     *
     * @param unexpected true if the failure is {@code unexpected}, false if {@code normal}
     */
    void recordFailure(boolean unexpected) {
        consecutiveSuccesses = 0;
        if (unexpected && !inExtendedRegime) {
            inExtendedRegime = true;
            attempts = 1;
            return;
        }
        attempts++;
    }

    /**
     * @return the wait in milliseconds before the next poll
     */
    long nextDelayMillis() {
        // A normal failure does not grow the delay, and a success shows the service works. Only
        // an unresolved unexpected failure waits longer than the poll interval.
        if (!inExtendedRegime || consecutiveSuccesses > 0) {
            return pollIntervalMillis;
        }
        return Math.max(pollIntervalMillis, extended.jitteredDelayMillis(attempts, random));
    }
}
