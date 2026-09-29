package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * Unit tests for {@link PollingRetryState}.
 */
public class PollingRetryStateTest {
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

    private static PollingRetryState polling(long pollIntervalMillis, Random random) {
        return new PollingRetryState(pollIntervalMillis, RetryRegime.EXTENDED, random);
    }

    @Test
    public void defaultResetThresholdIsTwoSuccesses() {
        assertEquals(2, PollingRetryState.RESET_THRESHOLD_SUCCESSES);
    }

    @Test
    public void delayBeforeAnyFailureIsThePollInterval() {
        assertEquals(10 * SECOND, polling(10 * SECOND, NO_JITTER).nextDelayMillis());
    }

    @Test
    public void normalFailuresWaitThePollInterval() {
        PollingRetryState state = polling(10 * SECOND, MAX_JITTER);
        for (int i = 0; i < 5; i++) {
            state.recordFailure(false);
            assertEquals(10 * SECOND, state.nextDelayMillis());
        }
    }

    @Test
    public void unexpectedFailureWaitsTheExtendedInitialDelayThenDoubles() {
        PollingRetryState state = polling(10 * SECOND, NO_JITTER);
        state.recordFailure(true);
        assertEquals(5 * MINUTE, state.nextDelayMillis());
        state.recordFailure(false);
        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void extendedDelayIsFlooredAtThePollInterval() {
        PollingRetryState state = polling(10 * MINUTE, NO_JITTER);
        state.recordFailure(true);
        assertEquals(10 * MINUTE, state.nextDelayMillis());
        state.recordFailure(false);
        assertEquals(20 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void extendedCeilingIsAtLeastThePollInterval() {
        PollingRetryState state = polling(90 * MINUTE, NO_JITTER);
        state.recordFailure(true);
        state.recordFailure(false);
        state.recordFailure(false);
        assertEquals(90 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void successReturnsToPollIntervalWithoutClearingTheBackoff() {
        PollingRetryState state = polling(10 * SECOND, NO_JITTER);
        state.recordFailure(true);
        state.recordSuccess();
        assertEquals(10 * SECOND, state.nextDelayMillis());

        state.recordFailure(false);
        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void twoConsecutiveSuccessesClearTheBackoff() {
        PollingRetryState state = polling(10 * SECOND, NO_JITTER);
        state.recordFailure(true);
        state.recordSuccess();
        state.recordSuccess();

        state.recordFailure(false);
        assertEquals(10 * SECOND, state.nextDelayMillis());
        state.recordFailure(true);
        assertEquals(5 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void failureRestartsTheSuccessCount() {
        PollingRetryState state = polling(10 * SECOND, NO_JITTER);
        state.recordFailure(true);
        state.recordSuccess();
        state.recordFailure(false);
        state.recordSuccess();
        state.recordFailure(false);
        assertEquals(20 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void defaultExtendedRegimeIsFiveMinutesWithJitter() {
        PollingRetryState state = new PollingRetryState(30 * SECOND);
        assertEquals(30 * SECOND, state.nextDelayMillis());
        state.recordFailure(false);
        assertEquals(30 * SECOND, state.nextDelayMillis());

        state.recordFailure(true);
        long wait = state.nextDelayMillis();
        assertTrue("wait " + wait, wait > RetryRegime.EXTENDED_INITIAL_DELAY_MILLIS / 2
                && wait <= RetryRegime.EXTENDED_INITIAL_DELAY_MILLIS);
    }
}
