package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * Backoff state for a long-running component such as a streaming or polling data source.
 * <p>
 * The owning component reports each success and failure, then asks how long to wait before its
 * next attempt. A failure is either {@code normal} or {@code unexpected}. An {@code unexpected}
 * failure, such as an HTTP 401, moves the component to a much longer backoff, which it leaves
 * only after enough healthy operation.
 * <p>
 * Timestamps are passed in explicitly. Any monotonic millisecond clock may be used, as long as
 * the same clock is used for every call.
 * <p>
 * This class is not thread-safe. The owning component must serialize access to it.
 */
final class RetryState {
    /**
     * The normal-regime ceiling for the wait between attempts.
     */
    static final long NORMAL_MAX_DELAY_MILLIS = 30_000L;
    /**
     * The base delay for the first attempt after an {@code unexpected} failure.
     */
    static final long EXTENDED_INITIAL_DELAY_MILLIS = 5L * 60_000L;
    /**
     * The extended-regime ceiling for the wait between attempts.
     */
    static final long EXTENDED_MAX_DELAY_MILLIS = 60L * 60_000L;
    /**
     * A streaming component resets its retry state after this much continuous healthy operation.
     */
    static final long STREAMING_RESET_THRESHOLD_MILLIS = 60_000L;
    /**
     * A polling component resets its retry state after this many consecutive successful polls.
     */
    static final int POLLING_RESET_THRESHOLD_SUCCESSES = 2;

    // Caps the doubling so that initialDelay * 2^exponent cannot overflow.
    private static final int MAX_EXPONENT = 30;

    private final long normalInitialDelayMillis;
    private final long normalMaxDelayMillis;
    private final long extendedInitialDelayMillis;
    private final long extendedMaxDelayMillis;
    // The interval at which the component operates when healthy, or zero if it has none.
    private final long operatingCadenceMillis;
    // Duration-based reset threshold, or zero if this component does not use one.
    private final long healthyResetThresholdMillis;
    // Count-based reset threshold, or zero if this component does not use one.
    private final int successResetThreshold;
    private final Random random;

    private int attempts = 0;
    private boolean extended = false;
    private boolean lastOperationFailed = false;
    // Start of the current stretch of healthy operation, or zero if not currently healthy.
    private long healthySinceMillis = 0;
    private int consecutiveSuccesses = 0;

    /**
     * Creates the retry state for a streaming data source or synchronizer. Normal failures back off
     * from the configured initial reconnect delay. The state resets after
     * {@link #STREAMING_RESET_THRESHOLD_MILLIS} of healthy operation.
     *
     * @param initialReconnectDelayMillis the configured initial reconnect delay, which may be zero
     * @return the retry state
     */
    @NonNull
    static RetryState forStreaming(long initialReconnectDelayMillis) {
        long initial = Math.max(0, initialReconnectDelayMillis);
        return new RetryState(
                initial,
                Math.max(NORMAL_MAX_DELAY_MILLIS, initial),
                Math.max(EXTENDED_INITIAL_DELAY_MILLIS, initial),
                Math.max(EXTENDED_MAX_DELAY_MILLIS, initial),
                0,
                STREAMING_RESET_THRESHOLD_MILLIS,
                0,
                new Random());
    }

    /**
     * Creates the retry state for a polling data source or synchronizer. Normal failures keep the
     * poll interval, and no wait is ever shorter than it. The state resets after
     * {@link #POLLING_RESET_THRESHOLD_SUCCESSES} consecutive successful polls.
     *
     * @param pollIntervalMillis the configured poll interval, which must be positive
     * @return the retry state
     */
    @NonNull
    static RetryState forPolling(long pollIntervalMillis) {
        long interval = Math.max(1, pollIntervalMillis);
        return new RetryState(
                interval,
                interval,
                Math.max(EXTENDED_INITIAL_DELAY_MILLIS, interval),
                Math.max(EXTENDED_MAX_DELAY_MILLIS, interval),
                interval,
                0,
                POLLING_RESET_THRESHOLD_SUCCESSES,
                new Random());
    }

    /**
     * Creates the retry state for one synchronizer slot. Every failure, {@code normal} or
     * {@code unexpected}, backs off in the extended regime. The state resets after
     * {@link #STREAMING_RESET_THRESHOLD_MILLIS} of healthy operation.
     *
     * @return the retry state
     */
    @NonNull
    static RetryState forSynchronizerSlot() {
        return new RetryState(
                EXTENDED_INITIAL_DELAY_MILLIS,
                EXTENDED_MAX_DELAY_MILLIS,
                EXTENDED_INITIAL_DELAY_MILLIS,
                EXTENDED_MAX_DELAY_MILLIS,
                0,
                STREAMING_RESET_THRESHOLD_MILLIS,
                0,
                new Random());
    }

    /**
     * Creates a retry state with explicit parameters, for tests. Production code should use one
     * of the static factories.
     *
     * @param normalInitialDelayMillis    base delay for the first retry in the normal regime
     * @param normalMaxDelayMillis        ceiling on the wait in the normal regime
     * @param extendedInitialDelayMillis  base delay for the first retry in the extended regime,
     *                                    raised to the normal initial delay if smaller
     * @param extendedMaxDelayMillis      ceiling on the wait in the extended regime
     * @param operatingCadenceMillis      the operating cadence, or zero if the component has none
     * @param healthyResetThresholdMillis how long healthy operation must last before the state
     *                                    resets, or zero if this component does not reset on
     *                                    duration
     * @param successResetThreshold       how many consecutive successes reset the state, or zero
     *                                    if this component does not reset on a count
     * @param random                      source of jitter
     */
    RetryState(
            long normalInitialDelayMillis,
            long normalMaxDelayMillis,
            long extendedInitialDelayMillis,
            long extendedMaxDelayMillis,
            long operatingCadenceMillis,
            long healthyResetThresholdMillis,
            int successResetThreshold,
            @NonNull Random random
    ) {
        this.normalInitialDelayMillis = Math.max(0, normalInitialDelayMillis);
        this.normalMaxDelayMillis = Math.max(this.normalInitialDelayMillis, normalMaxDelayMillis);
        this.extendedInitialDelayMillis = Math.max(this.normalInitialDelayMillis, extendedInitialDelayMillis);
        this.extendedMaxDelayMillis = Math.max(this.extendedInitialDelayMillis, extendedMaxDelayMillis);
        this.operatingCadenceMillis = Math.max(0, operatingCadenceMillis);
        this.healthyResetThresholdMillis = Math.max(0, healthyResetThresholdMillis);
        this.successResetThreshold = Math.max(0, successResetThreshold);
        this.random = random;
    }

    /**
     * Records healthy operation, such as a payload received on a stream or a successful poll.
     * Enough healthy operation resets the state. After a success the next wait is the operating
     * cadence, even if the state has not reset.
     *
     * @param nowMillis the current time
     */
    void recordSuccess(long nowMillis) {
        lastOperationFailed = false;
        if (healthyResetThresholdMillis > 0 && healthySinceMillis == 0) {
            healthySinceMillis = nowMillis;
        }
        if (successResetThreshold > 0) {
            consecutiveSuccesses++;
            if (consecutiveSuccesses >= successResetThreshold) {
                reset();
            }
        }
    }

    /**
     * Records a failure. Call this before {@link #nextDelayMillis()}.
     *
     * @param unexpected true if the failure is classified as {@code unexpected}, false if
     *                   {@code normal}
     * @param nowMillis  the current time
     */
    void recordFailure(boolean unexpected, long nowMillis) {
        if (healthyResetThresholdMillis > 0 && healthySinceMillis != 0
                && nowMillis - healthySinceMillis >= healthyResetThresholdMillis) {
            reset();
        }
        healthySinceMillis = 0;
        consecutiveSuccesses = 0;
        lastOperationFailed = true;

        if (unexpected && !extended) {
            // Start the attempt count over so the first extended wait is the extended initial
            // delay.
            extended = true;
            attempts = 1;
        } else {
            attempts++;
        }
    }

    /**
     * Clears the retry state back to no attempts and the normal regime.
     */
    void reset() {
        attempts = 0;
        extended = false;
        consecutiveSuccesses = 0;
    }

    /**
     * Computes how long to wait before the next attempt. After a success this is the operating
     * cadence. After a failure it is an exponential backoff with jitter, never less than the
     * operating cadence.
     *
     * @return the wait in milliseconds
     */
    long nextDelayMillis() {
        if (!lastOperationFailed) {
            return operatingCadenceMillis;
        }
        long initial = extended ? extendedInitialDelayMillis : normalInitialDelayMillis;
        long max = extended ? extendedMaxDelayMillis : normalMaxDelayMillis;
        int exponent = Math.min(Math.max(0, attempts - 1), MAX_EXPONENT);
        long base = initial * (1L << exponent);
        if (base < 0 || base > max) { // negative means the multiplication overflowed
            base = max;
        }
        long jitter = base > 1 ? (long) (random.nextDouble() * (base / 2.0)) : 0;
        return Math.max(operatingCadenceMillis, base - jitter);
    }

    /**
     * @return the number of failures counted since the last reset
     */
    int getAttempts() {
        return attempts;
    }

    /**
     * @return true if an {@code unexpected} failure has moved this component to the extended
     * regime and no reset has happened since
     */
    boolean isInExtendedRegime() {
        return extended;
    }
}
