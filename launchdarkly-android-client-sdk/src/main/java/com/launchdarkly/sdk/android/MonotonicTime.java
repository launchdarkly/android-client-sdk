package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.concurrent.TimeUnit;

/**
 * A point in time on the monotonic clock of {@link System#nanoTime()}. Changes to the system clock do not affect it.
 * The value has no meaning as a date, so it is only useful to compare with other {@code MonotonicTime} values.
 */
final class MonotonicTime {
    private final long nanos;

    private MonotonicTime(long nanos) {
        this.nanos = nanos;
    }

    /**
     * The current time.
     *
     * @return the current time
     */
    @NonNull
    static MonotonicTime now() {
        return new MonotonicTime(System.nanoTime());
    }

    /**
     * A time with the given value of {@link System#nanoTime()}. Visible for testing.
     *
     * @param nanos the value of {@link System#nanoTime()}
     * @return the time
     */
    @NonNull
    static MonotonicTime ofNanos(long nanos) {
        return new MonotonicTime(nanos);
    }

    /**
     * The time after the given number of milliseconds.
     *
     * @param millis the milliseconds to add
     * @return the later time
     */
    @NonNull
    MonotonicTime plusMillis(long millis) {
        return new MonotonicTime(nanos + TimeUnit.MILLISECONDS.toNanos(millis));
    }

    /**
     * True if this time is before the other time.
     *
     * @param other the other time
     * @return true if this time is before the other time
     */
    boolean isBefore(@NonNull MonotonicTime other) {
        // The nanosecond count can overflow, so compare the difference instead of the counts.
        return nanos - other.nanos < 0;
    }

    /**
     * The nanoseconds from this time until the other time. It is negative if the other time is before this time.
     *
     * @param other the other time
     * @return the nanoseconds until the other time
     */
    long nanosUntil(@NonNull MonotonicTime other) {
        return other.nanos - nanos;
    }
}
