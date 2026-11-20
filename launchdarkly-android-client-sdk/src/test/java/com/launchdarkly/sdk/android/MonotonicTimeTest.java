package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class MonotonicTimeTest {
    @Test
    public void timeAfterOverflowIsAfterTimeBeforeOverflow() {
        MonotonicTime beforeOverflow = MonotonicTime.ofNanos(Long.MAX_VALUE);

        // Move one second past the point where the nanosecond count overflows.
        MonotonicTime afterOverflow = beforeOverflow.plusMillis(1000);

        // The later time compares as later.
        assertTrue(beforeOverflow.isBefore(afterOverflow));
        assertFalse(afterOverflow.isBefore(beforeOverflow));
    }

    @Test
    public void nanosUntilMeasuresDurationAcrossOverflow() {
        MonotonicTime beforeOverflow = MonotonicTime.ofNanos(Long.MAX_VALUE);

        // Move one second past the point where the nanosecond count overflows.
        MonotonicTime afterOverflow = beforeOverflow.plusMillis(1000);

        // The duration is one second forward, and negative one second back.
        assertEquals(TimeUnit.SECONDS.toNanos(1), beforeOverflow.nanosUntil(afterOverflow));
        assertEquals(-TimeUnit.SECONDS.toNanos(1), afterOverflow.nanosUntil(beforeOverflow));
    }
}
