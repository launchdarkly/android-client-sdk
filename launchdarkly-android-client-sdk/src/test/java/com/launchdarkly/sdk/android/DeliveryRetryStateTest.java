package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;
import java.util.concurrent.TimeUnit;

public class DeliveryRetryStateTest {
    private static final long SECOND = 1_000;
    private static final long FLUSH_INTERVAL = 30 * SECOND;

    private final DeliveryRetryState state = new DeliveryRetryState(FLUSH_INTERVAL);

    @Test
    public void nothingWaitsBeforeTheFirstFailure() {
        assertEquals(0, state.remainingWaitNanos());
    }

    @Test
    public void ordinaryFailuresDoubleTheWaitUpToTheFlushInterval() {
        long[] expected = { 1, 2, 4, 8, 16, 30, 30 };
        for (long seconds : expected) {
            assertEquals(seconds * SECOND, state.recordFailure(false, -1));
        }
    }

    @Test
    public void theNormalCeilingIsNeverBelowASecond() {
        DeliveryRetryState shortInterval = new DeliveryRetryState(200);
        shortInterval.recordFailure(false, -1);
        assertEquals(SECOND, shortInterval.recordFailure(false, -1));
    }

    @Test
    public void aRefusalWaitsAboutEightAndAHalfMinutesAndDoublesToAnHour() {
        assertEquals(512 * SECOND, state.recordFailure(true, -1));
        assertEquals(1_024 * SECOND, state.recordFailure(true, -1));
        assertEquals(2_048 * SECOND, state.recordFailure(true, -1));
        assertEquals(TimeUnit.HOURS.toMillis(1), state.recordFailure(true, -1));
    }

    @Test
    public void anOrdinaryFailureAfterARefusalKeepsTheLongerCeiling() {
        state.recordFailure(true, -1);
        assertEquals(1_024 * SECOND, state.recordFailure(false, -1));
    }

    @Test
    public void anAcceptedDeliveryPutsEverythingBack() {
        state.recordFailure(true, -1);
        state.recordFailure(false, -1);
        state.recordSuccess();
        assertEquals(0, state.remainingWaitNanos());
        assertEquals(SECOND, state.recordFailure(false, -1));
    }

    @Test
    public void aRetryAfterSetsTheWaitJitteredToNoLessThanHalfOfIt() {
        for (int i = 0; i < 100; i++) {
            long wait = state.recordFailure(false, 10 * SECOND);
            assertTrue(wait + "ms", wait >= 5 * SECOND && wait <= 10 * SECOND);
        }
    }

    @Test
    public void aRetryAfterIsCappedAtAnHour() {
        long wait = new DeliveryRetryState(SECOND, FLUSH_INTERVAL, TimeUnit.HOURS.toMillis(1), new FixedRandom(0))
                .recordFailure(false, TimeUnit.HOURS.toMillis(5));
        assertEquals(TimeUnit.HOURS.toMillis(1), wait);
    }

    @Test
    public void aFailureWithARetryAfterStillCountsTowardTheBackoff() {
        state.recordFailure(false, -1);
        state.recordFailure(false, 0);
        assertEquals(4 * SECOND, state.recordFailure(false, -1));
    }

    @Test
    public void aFailureDuringTheWaitLeavesTheBackoffWhereItWas() {
        state.recordFailure(true, -1);
        long waiting = state.remainingWaitNanos();
        for (int i = 0; i < 5; i++) {
            state.recordFailureDuringWait(true, -1);
        }

        assertTrue(state.remainingWaitNanos() <= waiting);
        // The next step is the one after the first refusal, not five steps on.
        assertEquals(1_024 * SECOND, state.recordFailure(true, -1));
    }

    @Test
    public void aRetryAfterDuringTheWaitCanOnlyLengthenIt() {
        DeliveryRetryState fixed = new DeliveryRetryState(SECOND, FLUSH_INTERVAL, TimeUnit.HOURS.toMillis(1),
                new FixedRandom(0));
        fixed.recordFailure(true, -1);
        long waiting = fixed.remainingWaitNanos();

        fixed.recordFailureDuringWait(false, 10 * SECOND);
        assertTrue(fixed.remainingWaitNanos() <= waiting);

        fixed.recordFailureDuringWait(false, TimeUnit.MINUTES.toMillis(20));
        assertTrue(fixed.remainingWaitNanos() > TimeUnit.MINUTES.toNanos(19));
    }

    @Test
    public void aFailureDuringAWaitThatHasRunOutIsAnOrdinaryOne() throws InterruptedException {
        DeliveryRetryState quick = new DeliveryRetryState(20, 1_000, 1_000, new Random());
        quick.recordFailure(false, -1);
        Thread.sleep(40);

        quick.recordFailureDuringWait(false, -1);

        assertTrue(quick.remainingWaitNanos() > TimeUnit.MILLISECONDS.toNanos(20));
    }

    @Test
    public void theWaitRunsOut() throws InterruptedException {
        DeliveryRetryState quick = new DeliveryRetryState(20, 20, 20, new Random());
        quick.recordFailure(false, -1);
        assertTrue(quick.remainingWaitNanos() > 0);
        Thread.sleep(40);
        assertEquals(0, quick.remainingWaitNanos());
    }

    private static final class FixedRandom extends Random {
        private final double value;

        FixedRandom(double value) {
            this.value = value;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }
}
