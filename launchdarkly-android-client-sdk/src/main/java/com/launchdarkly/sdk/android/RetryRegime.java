package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * The delay bounds for one retry regime.
 */
final class RetryRegime {
    /**
     * The base delay for the first attempt after an {@code unexpected} failure.
     */
    static final long EXTENDED_INITIAL_DELAY_MILLIS = 5L * 60_000L;
    /**
     * The largest delay after an {@code unexpected} failure.
     */
    static final long EXTENDED_MAX_DELAY_MILLIS = 60L * 60_000L;
    /**
     * The regime entered after an {@code unexpected} failure.
     */
    static final RetryRegime EXTENDED =
            new RetryRegime(EXTENDED_INITIAL_DELAY_MILLIS, EXTENDED_MAX_DELAY_MILLIS);

    // Caps the doubling so that initialDelayMillis * 2^exponent cannot overflow.
    private static final int MAX_EXPONENT = 30;

    /**
     * The base delay for the first attempt in this regime.
     */
    final long initialDelayMillis;
    /**
     * The largest delay this regime produces.
     */
    final long maxDelayMillis;

    /**
     * @param initialDelayMillis the base delay for the first attempt, floored at zero
     * @param maxDelayMillis     the largest delay, raised to the initial delay if smaller
     */
    RetryRegime(long initialDelayMillis, long maxDelayMillis) {
        this.initialDelayMillis = Math.max(0, initialDelayMillis);
        this.maxDelayMillis = Math.max(this.initialDelayMillis, maxDelayMillis);
    }

    /**
     * @param floorMillis the smallest value allowed for either bound
     * @return a regime with the same bounds, except that neither is below the floor
     */
    @NonNull
    RetryRegime atLeast(long floorMillis) {
        return new RetryRegime(
                Math.max(initialDelayMillis, floorMillis), Math.max(maxDelayMillis, floorMillis));
    }

    /**
     * The exponential backoff for an attempt, less a random amount of up to half of it.
     *
     * @param attempts the number of failures so far, starting at 1
     * @param random   source of jitter
     * @return the wait in milliseconds
     */
    long jitteredDelayMillis(int attempts, @NonNull Random random) {
        int exponent = Math.min(Math.max(0, attempts - 1), MAX_EXPONENT);
        long base = initialDelayMillis * (1L << exponent);
        if (base < 0 || base > maxDelayMillis) { // negative means the multiplication overflowed
            base = maxDelayMillis;
        }
        long jitter = base > 1 ? (long) (random.nextDouble() * (base / 2.0)) : 0;
        return base - jitter;
    }
}
