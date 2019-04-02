package com.launchdarkly.android;

import android.support.test.annotation.UiThreadTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.launchdarkly.android.test.TestActivity;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

/**
 * Created by jamesthacker on 4/2/18.
 */

@RunWith(AndroidJUnit4.class)
public class ThrottlerTest {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    private final AtomicBoolean hasRun = new AtomicBoolean(false);
    private Throttler throttler;
    private static final long MAX_RETRY_TIME_MS = 600000;
    private static final long RETRY_TIME_MS = 1000;

    @Before
    public void setUp() {
         throttler = new Throttler(new Runnable() {
            @Override
            public void run() {
                hasRun.set(true);
            }
        }, RETRY_TIME_MS, MAX_RETRY_TIME_MS);

    }

    @UiThreadTest
    @Test
    public void testFirstRunIsInstant() {
        throttler.attemptRun();
        boolean result = this.hasRun.getAndSet(false);
        assertTrue(result);
    }

    @Ignore("Useful for inspecting jitter values empirically")
    public void inspectJitter() {
        for (int i = 0; i < 100; i++) {
            long jitterVal = throttler.calculateJitterVal(i);
            System.out.println("With jitter, retry " + i + ": " + throttler.backoffWithJitter(jitterVal));
        }
    }

    @UiThreadTest
    @Test
    public void testRespectsMaxRetryTime() {
        assertEquals(throttler.calculateJitterVal(300), MAX_RETRY_TIME_MS);
    }
}
