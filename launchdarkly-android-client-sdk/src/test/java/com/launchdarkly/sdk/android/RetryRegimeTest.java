package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * Unit tests for {@link RetryRegime}.
 */
public class RetryRegimeTest {
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60 * SECOND;

    /** A random source that always yields the same fraction, so jitter is predictable. */
    private static Random fixedRandom(final double fraction) {
        return new Random() {
            @Override
            public double nextDouble() {
                return fraction;
            }
        };
    }

    private static final Random NO_JITTER = fixedRandom(0);
    private static final Random MAX_JITTER = fixedRandom(0.999_999);

    @Test
    public void extendedRegimeIsFiveMinutesToOneHour() {
        assertEquals(5 * MINUTE, RetryRegime.EXTENDED.initialDelayMillis);
        assertEquals(60 * MINUTE, RetryRegime.EXTENDED.maxDelayMillis);
    }

    @Test
    public void delaysDoubleFromTheInitialDelayUpToTheCeiling() {
        RetryRegime regime = new RetryRegime(SECOND, 30 * SECOND);
        long[] expected = {
                SECOND, 2 * SECOND, 4 * SECOND, 8 * SECOND, 16 * SECOND, 30 * SECOND, 30 * SECOND};
        for (int i = 0; i < expected.length; i++) {
            int attempts = i + 1;
            long wait = regime.jitteredDelayMillis(attempts, NO_JITTER);
            assertEquals("attempt " + attempts, expected[i], wait);
        }
    }

    @Test
    public void jitterSubtractsUpToHalfOfTheDelay() {
        RetryRegime regime = new RetryRegime(SECOND, 30 * SECOND);
        // Jitter is chosen from [0, T/2), so the smallest wait is just above T/2.
        long minWait = regime.jitteredDelayMillis(1, MAX_JITTER);
        assertTrue("wait " + minWait, minWait > SECOND / 2 && minWait <= SECOND);
    }

    @Test
    public void ceilingIsRaisedToTheInitialDelay() {
        RetryRegime regime = new RetryRegime(10 * MINUTE, 8 * MINUTE);
        assertEquals(10 * MINUTE, regime.maxDelayMillis);
        assertEquals(10 * MINUTE, regime.jitteredDelayMillis(2, NO_JITTER));
    }

    @Test
    public void atLeastRaisesBothBoundsToTheFloor() {
        RetryRegime raised = RetryRegime.EXTENDED.atLeast(90 * MINUTE);
        assertEquals(90 * MINUTE, raised.initialDelayMillis);
        assertEquals(90 * MINUTE, raised.maxDelayMillis);

        RetryRegime unchanged = RetryRegime.EXTENDED.atLeast(SECOND);
        assertEquals(5 * MINUTE, unchanged.initialDelayMillis);
        assertEquals(60 * MINUTE, unchanged.maxDelayMillis);
    }

    @Test
    public void manyAttemptsStayAtTheCeilingWithoutOverflow() {
        RetryRegime regime = new RetryRegime(SECOND, 30 * SECOND);
        for (int attempts = 1; attempts <= 200; attempts++) {
            long wait = regime.jitteredDelayMillis(attempts, NO_JITTER);
            assertTrue("wait " + wait, wait > 0 && wait <= 30 * SECOND);
        }
    }
}
