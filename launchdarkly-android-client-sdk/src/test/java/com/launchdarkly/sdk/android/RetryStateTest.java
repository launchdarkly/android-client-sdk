package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * Unit tests for {@link RetryState}.
 */
public class RetryStateTest {
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

    private static RetryState streaming(Random random) {
        return new RetryState(
                SECOND,
                RetryState.NORMAL_MAX_DELAY_MILLIS,
                RetryState.EXTENDED_INITIAL_DELAY_MILLIS,
                RetryState.EXTENDED_MAX_DELAY_MILLIS,
                0,
                RetryState.STREAMING_RESET_THRESHOLD_MILLIS,
                0,
                random);
    }

    private static RetryState polling(long pollIntervalMillis, Random random) {
        return new RetryState(
                pollIntervalMillis,
                pollIntervalMillis,
                Math.max(RetryState.EXTENDED_INITIAL_DELAY_MILLIS, pollIntervalMillis),
                Math.max(RetryState.EXTENDED_MAX_DELAY_MILLIS, pollIntervalMillis),
                pollIntervalMillis,
                0,
                RetryState.POLLING_RESET_THRESHOLD_SUCCESSES,
                random);
    }

    // ---- defaults ----

    @Test
    public void defaultsAreThirtySecondNormalCeilingAndFiveMinuteToOneHourExtendedRegime() {
        assertEquals(30 * SECOND, RetryState.NORMAL_MAX_DELAY_MILLIS);
        assertEquals(5 * MINUTE, RetryState.EXTENDED_INITIAL_DELAY_MILLIS);
        assertEquals(60 * MINUTE, RetryState.EXTENDED_MAX_DELAY_MILLIS);
        assertEquals(60 * SECOND, RetryState.STREAMING_RESET_THRESHOLD_MILLIS);
        assertEquals(2, RetryState.POLLING_RESET_THRESHOLD_SUCCESSES);
    }

    // ---- initial state ----

    @Test
    public void startsWithNoAttemptsInNormalRegime() {
        RetryState state = streaming(NO_JITTER);
        assertEquals(0, state.getAttempts());
        assertFalse(state.isInExtendedRegime());
    }

    @Test
    public void delayBeforeAnyFailureIsTheOperatingCadence() {
        assertEquals(0, streaming(NO_JITTER).nextDelayMillis());
        assertEquals(10 * SECOND, polling(10 * SECOND, NO_JITTER).nextDelayMillis());
    }

    // ---- normal regime ----

    @Test
    public void normalFailuresDoubleTheDelayUpToTheNormalCeiling() {
        RetryState state = streaming(NO_JITTER);
        long[] expected = {SECOND, 2 * SECOND, 4 * SECOND, 8 * SECOND, 16 * SECOND, 30 * SECOND, 30 * SECOND};
        for (int i = 0; i < expected.length; i++) {
            state.recordFailure(false, 0);
            assertEquals("attempt " + (i + 1), i + 1, state.getAttempts());
            assertEquals("delay after attempt " + (i + 1), expected[i], state.nextDelayMillis());
        }
        assertFalse(state.isInExtendedRegime());
    }

    // ---- jitter ----

    @Test
    public void jitterSubtractsUpToHalfOfTheBaseDelay() {
        RetryState noJitter = streaming(NO_JITTER);
        RetryState maxJitter = streaming(MAX_JITTER);
        noJitter.recordFailure(false, 0);
        maxJitter.recordFailure(false, 0);
        assertEquals(SECOND, noJitter.nextDelayMillis());
        // Jitter is chosen from [0, T/2), so the smallest wait is just above T/2.
        long minWait = maxJitter.nextDelayMillis();
        assertTrue("wait " + minWait, minWait > SECOND / 2 && minWait <= SECOND);
    }

    // ---- unexpected failures and the extended regime ----

    @Test
    public void unexpectedFailureMovesToExtendedRegimeStartingAtExtendedInitialDelay() {
        RetryState state = streaming(NO_JITTER);
        state.recordFailure(false, 0);
        state.recordFailure(false, 0);
        state.recordFailure(true, 0);

        assertTrue(state.isInExtendedRegime());
        assertEquals(1, state.getAttempts());
        assertEquals(5 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void extendedRegimeDoublesUpToTheExtendedCeiling() {
        RetryState state = streaming(NO_JITTER);
        state.recordFailure(true, 0);
        long[] expected = {5 * MINUTE, 10 * MINUTE, 20 * MINUTE, 40 * MINUTE, 60 * MINUTE, 60 * MINUTE};
        assertEquals(expected[0], state.nextDelayMillis());
        for (int i = 1; i < expected.length; i++) {
            state.recordFailure(false, 0);
            assertEquals("delay after attempt " + (i + 1), expected[i], state.nextDelayMillis());
        }
    }

    @Test
    public void normalFailureAfterUnexpectedStaysInExtendedRegime() {
        // Once raised, the ceiling and base stay raised until a reset.
        RetryState state = streaming(NO_JITTER);
        state.recordFailure(true, 0);
        state.recordFailure(false, 0);
        assertTrue(state.isInExtendedRegime());
        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void repeatedUnexpectedFailuresKeepCountingInExtendedRegime() {
        RetryState state = streaming(NO_JITTER);
        state.recordFailure(true, 0);
        state.recordFailure(true, 0);
        assertEquals(2, state.getAttempts());
        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void extendedDelaysAreNeverBelowTheNormalInitialDelay() {
        // A component whose normal initial delay exceeds the extended bounds uses the normal
        // initial delay in their place.
        RetryState state = new RetryState(10 * MINUTE, 10 * MINUTE, 5 * MINUTE, 8 * MINUTE, 0, 0, 0, NO_JITTER);
        state.recordFailure(true, 0);
        assertEquals(10 * MINUTE, state.nextDelayMillis());
        state.recordFailure(false, 0);
        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    // ---- reset ----

    @Test
    public void resetReturnsToNormalRegimeWithNoAttempts() {
        RetryState state = streaming(NO_JITTER);
        state.recordFailure(true, 0);
        state.recordFailure(false, 0);
        state.reset();

        assertEquals(0, state.getAttempts());
        assertFalse(state.isInExtendedRegime());
        state.recordFailure(false, 0);
        assertEquals(SECOND, state.nextDelayMillis());
    }

    @Test
    public void streamingHealthyForThresholdResetsWhenTheNextFailureIsRecorded() {
        RetryState state = streaming(NO_JITTER);
        state.recordFailure(true, 0);
        assertTrue(state.isInExtendedRegime());

        long connectedAt = 10 * SECOND;
        state.recordSuccess(connectedAt);
        state.recordFailure(false, connectedAt + RetryState.STREAMING_RESET_THRESHOLD_MILLIS);

        assertFalse(state.isInExtendedRegime());
        assertEquals(1, state.getAttempts());
        assertEquals(SECOND, state.nextDelayMillis());
    }

    @Test
    public void streamingHealthyForLessThanThresholdDoesNotReset() {
        RetryState state = streaming(NO_JITTER);
        state.recordFailure(true, 0);

        long connectedAt = 10 * SECOND;
        state.recordSuccess(connectedAt);
        state.recordFailure(false, connectedAt + RetryState.STREAMING_RESET_THRESHOLD_MILLIS - 1);

        assertTrue(state.isInExtendedRegime());
        assertEquals(2, state.getAttempts());
        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void streamingHealthyOperationIsMeasuredFromTheFirstMessageOnTheConnection() {
        RetryState state = streaming(NO_JITTER);
        state.recordFailure(true, 0);

        state.recordSuccess(10 * SECOND);
        state.recordSuccess(65 * SECOND); // must not move the marker
        state.recordFailure(false, 70 * SECOND); // 60 seconds since the first message

        assertFalse(state.isInExtendedRegime());
    }

    @Test
    public void streamingFailureRestartsHealthyOperationMeasurement() {
        // The marker from a previous connection does not count toward the next one.
        RetryState state = streaming(NO_JITTER);
        state.recordFailure(true, 0);

        state.recordSuccess(10 * SECOND);
        state.recordFailure(false, 20 * SECOND); // healthy for 10 seconds only
        state.recordFailure(false, 90 * SECOND); // no message on this connection

        assertTrue(state.isInExtendedRegime());
        assertEquals(3, state.getAttempts());
    }

    @Test
    public void streamingSuccessAloneDoesNotReset() {
        RetryState state = streaming(NO_JITTER);
        state.recordFailure(true, 0);
        state.recordSuccess(10 * SECOND);
        state.recordSuccess(10 * MINUTE);
        // The reset is applied when the next failure is recorded, and only then. Until that
        // point the regime is unchanged.
        assertTrue(state.isInExtendedRegime());
    }

    // ---- polling: operating cadence ----

    @Test
    public void pollingNormalFailureWaitsThePollInterval() {
        RetryState state = polling(10 * SECOND, MAX_JITTER);
        for (int i = 0; i < 5; i++) {
            state.recordFailure(false, 0);
            // Jitter cannot bring the wait below the cadence.
            assertEquals(10 * SECOND, state.nextDelayMillis());
        }
        assertFalse(state.isInExtendedRegime());
    }

    @Test
    public void pollingUnexpectedFailureWaitsTheExtendedInitialDelay() {
        RetryState state = polling(10 * SECOND, NO_JITTER);
        state.recordFailure(true, 0);
        assertEquals(5 * MINUTE, state.nextDelayMillis());
        state.recordFailure(false, 0);
        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void pollingExtendedDelayIsFlooredAtThePollInterval() {
        RetryState state = polling(10 * MINUTE, NO_JITTER);
        state.recordFailure(true, 0);
        assertEquals(10 * MINUTE, state.nextDelayMillis());
        state.recordFailure(false, 0);
        assertEquals(20 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void pollingExtendedCeilingIsAtLeastThePollInterval() {
        RetryState state = polling(90 * MINUTE, NO_JITTER);
        state.recordFailure(true, 0);
        state.recordFailure(false, 0);
        state.recordFailure(false, 0);
        assertEquals(90 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void pollingSuccessReturnsToCadenceWithoutResettingTheRegime() {
        // One success restores the cadence but does not clear the retry state.
        RetryState state = polling(10 * SECOND, NO_JITTER);
        state.recordFailure(true, 0);
        state.recordSuccess(0);

        assertEquals(10 * SECOND, state.nextDelayMillis());
        assertTrue(state.isInExtendedRegime());

        state.recordFailure(false, 0);
        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void pollingTwoConsecutiveSuccessesReset() {
        RetryState state = polling(10 * SECOND, NO_JITTER);
        state.recordFailure(true, 0);
        state.recordSuccess(0);
        state.recordSuccess(0);

        assertFalse(state.isInExtendedRegime());
        assertEquals(0, state.getAttempts());
        state.recordFailure(false, 0);
        assertEquals(10 * SECOND, state.nextDelayMillis());
    }

    @Test
    public void pollingFailureRestartsTheSuccessCount() {
        // Successes must be consecutive.
        RetryState state = polling(10 * SECOND, NO_JITTER);
        state.recordFailure(true, 0);
        state.recordSuccess(0);
        state.recordFailure(false, 0);
        state.recordSuccess(0);

        assertTrue(state.isInExtendedRegime());
    }

    // ---- factories ----

    @Test
    public void forStreamingUsesConfiguredInitialReconnectDelayAsNormalInitialDelay() {
        RetryState state = RetryState.forStreaming(250);
        state.recordFailure(false, 0);
        long wait = state.nextDelayMillis();
        assertTrue("wait " + wait, wait > 125 && wait <= 250);
        assertEquals(0, RetryState.forStreaming(250).nextDelayMillis());
    }

    @Test
    public void forStreamingWithZeroInitialDelayReconnectsImmediatelyInNormalRegime() {
        RetryState state = RetryState.forStreaming(0);
        state.recordFailure(false, 0);
        assertEquals(0, state.nextDelayMillis());
        state.recordFailure(true, 0);
        long wait = state.nextDelayMillis();
        assertTrue("wait " + wait, wait > RetryState.EXTENDED_INITIAL_DELAY_MILLIS / 2
                && wait <= RetryState.EXTENDED_INITIAL_DELAY_MILLIS);
    }

    @Test
    public void forPollingUsesPollIntervalAsCadence() {
        RetryState state = RetryState.forPolling(30 * SECOND);
        assertEquals(30 * SECOND, state.nextDelayMillis());
        state.recordFailure(false, 0);
        assertEquals(30 * SECOND, state.nextDelayMillis());
        state.recordFailure(true, 0);
        long wait = state.nextDelayMillis();
        assertTrue("wait " + wait, wait > RetryState.EXTENDED_INITIAL_DELAY_MILLIS / 2
                && wait <= RetryState.EXTENDED_INITIAL_DELAY_MILLIS);
    }

    // ---- robustness ----

    @Test
    public void manyFailuresStayAtTheCeilingWithoutOverflow() {
        RetryState state = streaming(NO_JITTER);
        for (int i = 0; i < 200; i++) {
            state.recordFailure(false, 0);
            long wait = state.nextDelayMillis();
            assertTrue("wait " + wait, wait > 0 && wait <= RetryState.NORMAL_MAX_DELAY_MILLIS);
        }
        state.recordFailure(true, 0);
        for (int i = 0; i < 200; i++) {
            state.recordFailure(false, 0);
            long wait = state.nextDelayMillis();
            assertTrue("wait " + wait, wait > 0 && wait <= RetryState.EXTENDED_MAX_DELAY_MILLIS);
        }
    }
}
