package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * Retry state for a long-running component such as a streaming or polling data source:
 * exponential backoff with jitter, in two regimes.
 * <p>
 * Every failure is classified as either {@code normal} or {@code unexpected}. A {@code normal}
 * failure advances the backoff within the current regime. An {@code unexpected} failure (for
 * example an HTTP 401 or 403, which is unlikely to resolve on its own quickly) moves the
 * component to the extended regime, whose delays run from minutes up to an hour, and the
 * component stays there until the reset threshold is met. No failure ever causes the component
 * to stop retrying.
 * <p>
 * The reset threshold differs for the two kinds of component:
 * <ul>
 *   <li>Streaming: continuous healthy operation for {@link #STREAMING_RESET_THRESHOLD_MILLIS},
 *       where healthy operation starts with the first payload received on a connection.</li>
 *   <li>Polling: {@link #POLLING_RESET_THRESHOLD_SUCCESSES} consecutive successful polls.</li>
 * </ul>
 * <p>
 * Timestamps are passed in explicitly so that the state machine is deterministic in tests. Any
 * monotonic millisecond clock may be used, as long as the same clock is used for every call.
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

    // 2^30 is far more doubling than any real delay needs, and keeps initialDelay * 2^exponent
    // from overflowing a long for any plausible configured delay.
    private static final int MAX_EXPONENT = 30;

    private final long normalInitialDelayMillis;
    private final long normalMaxDelayMillis;
    private final long extendedInitialDelayMillis;
    private final long extendedMaxDelayMillis;
    // The interval at which the component operates when healthy. The wait after a failure is
    // never shorter than this, and it is the wait after a success. Zero for a component with no
    // such interval, such as streaming.
    private final long operatingCadenceMillis;
    // Duration-based reset threshold; zero if this component does not use one.
    private final long healthyResetThresholdMillis;
    // Count-based reset threshold; zero if this component does not use one.
    private final int successResetThreshold;
    private final Random random;

    private int attempts = 0;
    private boolean extended = false;
    private boolean lastOperationFailed = false;
    // Start of the current stretch of healthy operation, or zero if not currently healthy.
    private long healthySinceMillis = 0;
    private int consecutiveSuccesses = 0;

    /**
     * Creates the retry state for a streaming data source or synchronizer.
     * <p>
     * The normal regime backs off from the configured initial reconnect delay up to
     * {@link #NORMAL_MAX_DELAY_MILLIS}. The extended regime backs off from
     * {@link #EXTENDED_INITIAL_DELAY_MILLIS} up to {@link #EXTENDED_MAX_DELAY_MILLIS}. The state
     * resets after {@link #STREAMING_RESET_THRESHOLD_MILLIS} of healthy operation.
     *
     * @param initialReconnectDelayMillis the configured initial reconnect delay; may be zero
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
     * Creates the retry state for a polling data source or synchronizer.
     * <p>
     * A polling component's operating cadence is its poll interval. In the normal regime it keeps
     * polling at that interval after a failure. In the extended regime it backs off from
     * {@link #EXTENDED_INITIAL_DELAY_MILLIS} up to {@link #EXTENDED_MAX_DELAY_MILLIS}, but never
     * more often than the poll interval. The state resets after
     * {@link #POLLING_RESET_THRESHOLD_SUCCESSES} consecutive successful polls.
     *
     * @param pollIntervalMillis the configured poll interval; must be positive
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
     * Creates the retry state that the FDv2 data source keeps for one synchronizer slot.
     * <p>
     * Only unexpected failures are recorded against a slot, so both regimes are bound to the
     * extended values: the slot waits {@link #EXTENDED_INITIAL_DELAY_MILLIS} after the first
     * unexpected error, doubling up to {@link #EXTENDED_MAX_DELAY_MILLIS}. The state resets after
     * {@link #STREAMING_RESET_THRESHOLD_MILLIS} of healthy operation by the slot's synchronizer.
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
     * Creates a retry state with explicit parameters. Production code should use one of the
     * static factories; this constructor exists so that tests can use short delays and a
     * deterministic random source.
     *
     * @param normalInitialDelayMillis    base delay for the first retry in the normal regime
     * @param normalMaxDelayMillis        ceiling on the wait in the normal regime
     * @param extendedInitialDelayMillis  base delay for the first retry in the extended regime;
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
     * Records healthy operation.
     * <p>
     * For a streaming component this is a payload received on the current connection; the first
     * such call after a connection is established starts the clock toward the duration-based
     * reset threshold, and later calls on the same connection do not move it. For a polling
     * component this is a successful poll, which counts toward the count-based reset threshold.
     * <p>
     * After a success the next wait is the operating cadence, even if the retry state has not
     * reset.
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
     * Records a failure and updates the retry state.
     * <p>
     * If this component resets on a duration and it had been healthy for at least that long when
     * the failure happened, the state is reset first so that the failure is counted against a
     * clean slate. Either way, measurement toward the reset threshold restarts.
     * <p>
     * Call this before {@link #nextDelayMillis()}.
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
            // Move to the extended regime and start its attempt count over, so the first
            // extended wait is the extended initial delay.
            extended = true;
            attempts = 1;
        } else {
            // A repeated unexpected failure keeps counting in the extended regime.
            attempts++;
        }
    }

    /**
     * Clears the retry state back to its initial values: no attempts, and the normal regime.
     */
    void reset() {
        attempts = 0;
        extended = false;
        consecutiveSuccesses = 0;
    }

    /**
     * Computes how long to wait before the next attempt.
     * <p>
     * If the most recent operation succeeded (or no operation has failed yet), this is the
     * operating cadence. Otherwise it is {@code T - J}, where {@code T} is the regime's initial
     * delay doubled once per attempt after the first and clamped to the regime's ceiling, and
     * {@code J} is a uniformly random jitter of up to half of {@code T}. The result is never less
     * than the operating cadence.
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
