package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * Computes the wait before a streaming connection is retried. The backoff clears after enough
 * continuous healthy operation.
 * <p>
 * This class is not thread-safe. The owning component must serialize access to it.
 */
final class StreamingRetryState {
    /**
     * The largest delay after a {@code normal} failure.
     */
    static final long NORMAL_MAX_DELAY_MILLIS = 30_000L;
    /**
     * The backoff clears after this much continuous healthy operation.
     */
    static final long RESET_THRESHOLD_MILLIS = 60_000L;

    private final RetryRegime normal;
    private final RetryRegime extended;
    private final long resetThresholdMillis;
    private final Random random;

    private boolean inExtendedRegime = false;
    private int attempts = 0;
    private boolean healthy = false;
    private long healthySinceMillis = 0;

    /**
     * @param initialReconnectDelayMillis the delay before the first reconnect after a
     *                                    {@code normal} failure, which may be zero
     */
    StreamingRetryState(long initialReconnectDelayMillis) {
        this(new RetryRegime(initialReconnectDelayMillis, NORMAL_MAX_DELAY_MILLIS),
                RetryRegime.EXTENDED.atLeast(initialReconnectDelayMillis),
                RESET_THRESHOLD_MILLIS,
                new Random());
    }

    /**
     * Creates a retry state with explicit regimes.
     *
     * @param normal               the regime for {@code normal} failures
     * @param extended             the regime entered by an {@code unexpected} failure
     * @param resetThresholdMillis how long healthy operation must last before the backoff clears
     * @param random               source of jitter
     */
    StreamingRetryState(
            @NonNull RetryRegime normal,
            @NonNull RetryRegime extended,
            long resetThresholdMillis,
            @NonNull Random random
    ) {
        this.normal = normal;
        this.extended = extended;
        this.resetThresholdMillis = resetThresholdMillis;
        this.random = random;
    }

    /**
     * Records healthy operation, such as a payload received on the stream. Healthy operation is
     * measured from the first call after a failure.
     *
     * @param nowMillis the current time in milliseconds. Only differences between times matter,
     *                  so any clock will do as long as every call on this instance uses the same
     *                  one.
     */
    void recordSuccess(long nowMillis) {
        if (!healthy) {
            healthy = true;
            healthySinceMillis = nowMillis;
        }
    }

    /**
     * Records a failure. Call this before {@link #nextDelayMillis()}.
     *
     * @param unexpected true if the failure is {@code unexpected}, false if {@code normal}
     * @param nowMillis  the current time in milliseconds. Only differences between times matter,
     *                   so any clock will do as long as every call on this instance uses the same
     *                   one.
     */
    void recordFailure(boolean unexpected, long nowMillis) {
        if (healthy && nowMillis - healthySinceMillis >= resetThresholdMillis) {
            // The connection worked for long enough that this failure starts the backoff over.
            inExtendedRegime = false;
            attempts = 0;
        }
        healthy = false;
        if (unexpected && !inExtendedRegime) {
            inExtendedRegime = true;
            attempts = 1;
            return;
        }
        attempts++;
    }

    /**
     * @return the wait in milliseconds before the next connection attempt
     */
    long nextDelayMillis() {
        return (inExtendedRegime ? extended : normal).jitteredDelayMillis(attempts, random);
    }
}
