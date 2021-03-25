package com.launchdarkly.android;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThrottlerTest {

    private Throttler throttler;
    private final AtomicBoolean hasRun = new AtomicBoolean(false);
    private final long MAX_RETRY_TIME_MS = 5_000;

    @Before
    public void setUp() {
        hasRun.set(false);
        throttler = new Throttler(() -> hasRun.set(true), 100, MAX_RETRY_TIME_MS);
    }

    @Test
    public void initialRunsInstant() {
        throttler.attemptRun();
        assertTrue(hasRun.get());

        // Second run is instant on fresh throttler to not penalize `init`.
        hasRun.set(false);
        throttler.attemptRun();
        assertTrue(hasRun.get());

        // Third run should be delayed
        hasRun.set(false);
        throttler.attemptRun();
        assertFalse(hasRun.get());
    }

    @Test
    public void delaysResetThrottle() throws InterruptedException {
        throttler.attemptRun();
        throttler.attemptRun();
        Thread.sleep(150);

        // Delay should allow third run to be instant
        hasRun.set(false);
        throttler.attemptRun();
        assertTrue(hasRun.get());

        // Confirms second run after delay is throttled
        hasRun.set(false);
        throttler.attemptRun();
        assertFalse(hasRun.get());
    }

    @Test
    public void canCancelledThrottledRun() throws InterruptedException {
        throttler.attemptRun();
        throttler.attemptRun();

        hasRun.set(false);
        throttler.attemptRun();
        throttler.cancel();

        Thread.sleep(250);
        assertFalse(hasRun.get());
    }

    @Test
    public void canScheduleRunAfterCancelled() throws InterruptedException {
        throttler.attemptRun();
        throttler.attemptRun();

        hasRun.set(false);
        throttler.attemptRun();
        throttler.cancel();
        throttler.attemptRun();

        Thread.sleep(1000);
        assertTrue(hasRun.get());
    }

    @Test
    public void testRespectsMaxRetryTime() {
        assertEquals(throttler.calculateJitterVal(300), MAX_RETRY_TIME_MS);
    }
}
