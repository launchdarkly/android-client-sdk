package com.launchdarkly.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ThrottlerTest {

    private Throttler throttler;
    private final AtomicBoolean hasRun = new AtomicBoolean(false);
    private final long MAX_RETRY_TIME_MS = 30_000;

    @Before
    public void setUp() {
        hasRun.set(false);
        throttler = new Throttler(() -> hasRun.set(true), 1_000, MAX_RETRY_TIME_MS);
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
        Thread.sleep(1_500);

        // Delay should allow third run to be instant
        hasRun.set(false);
        throttler.attemptRun();
        assertTrue(hasRun.get());

        // Confirms second run after delay is throttled
        hasRun.set(false);
        throttler.attemptRun();
        assertFalse(hasRun.get());
    }

    @Ignore("Useful for inspecting jitter values empirically")
    public void inspectJitter() {
        for (int i = 0; i < 100; i++) {
            long jitterVal = throttler.calculateJitterVal(i);
            System.out.println("With jitter, retry " + i + ": " + throttler.backoffWithJitter(jitterVal));
        }
    }

    @Test
    public void testRespectsMaxRetryTime() {
        assertEquals(throttler.calculateJitterVal(300), MAX_RETRY_TIME_MS);
    }
}
