package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * Unit tests for {@link StreamingRetryState}.
 */
public class StreamingRetryStateTest {
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long THRESHOLD = StreamingRetryState.RESET_THRESHOLD_MILLIS;

    private static final Random NO_JITTER = new Random() {
        @Override
        public double nextDouble() {
            return 0;
        }
    };

    private static StreamingRetryState streaming() {
        return new StreamingRetryState(
                new RetryRegime(SECOND, StreamingRetryState.NORMAL_MAX_DELAY_MILLIS),
                RetryRegime.EXTENDED,
                THRESHOLD,
                NO_JITTER);
    }

    @Test
    public void defaultsAreThirtySecondNormalCeilingAndOneMinuteResetThreshold() {
        assertEquals(30 * SECOND, StreamingRetryState.NORMAL_MAX_DELAY_MILLIS);
        assertEquals(60 * SECOND, StreamingRetryState.RESET_THRESHOLD_MILLIS);
    }

    // ---- regimes ----

    @Test
    public void normalFailuresBackOffInTheNormalRegime() {
        StreamingRetryState state = streaming();
        state.recordFailure(false, 0);
        assertEquals(SECOND, state.nextDelayMillis());
        state.recordFailure(false, 0);
        assertEquals(2 * SECOND, state.nextDelayMillis());
        state.recordFailure(false, 0);
        assertEquals(4 * SECOND, state.nextDelayMillis());
    }

    @Test
    public void unexpectedFailureMovesToExtendedRegimeStartingAtExtendedInitialDelay() {
        StreamingRetryState state = streaming();
        state.recordFailure(false, 0);
        state.recordFailure(false, 0);
        state.recordFailure(true, 0);
        assertEquals(5 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void normalFailureAfterUnexpectedStaysInExtendedRegime() {
        StreamingRetryState state = streaming();
        state.recordFailure(true, 0);
        state.recordFailure(false, 0);
        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void repeatedUnexpectedFailuresKeepCountingInExtendedRegime() {
        StreamingRetryState state = streaming();
        state.recordFailure(true, 0);
        state.recordFailure(true, 0);
        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    // ---- healthy operation ----

    @Test
    public void healthyForThresholdResetsWhenTheNextFailureIsRecorded() {
        StreamingRetryState state = streaming();
        state.recordFailure(true, 0);

        long connectedAt = 10 * SECOND;
        state.recordSuccess(connectedAt);
        state.recordFailure(false, connectedAt + THRESHOLD);

        assertEquals(SECOND, state.nextDelayMillis());
    }

    @Test
    public void healthyForLessThanThresholdDoesNotReset() {
        StreamingRetryState state = streaming();
        state.recordFailure(true, 0);

        long connectedAt = 10 * SECOND;
        state.recordSuccess(connectedAt);
        state.recordFailure(false, connectedAt + THRESHOLD - 1);

        assertEquals(10 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void healthyOperationIsMeasuredFromTheFirstMessageOnTheConnection() {
        StreamingRetryState state = streaming();
        state.recordFailure(true, 0);

        state.recordSuccess(10 * SECOND);
        state.recordSuccess(65 * SECOND); // must not move the marker
        state.recordFailure(false, 70 * SECOND); // 60 seconds since the first message

        assertEquals(SECOND, state.nextDelayMillis());
    }

    @Test
    public void failureRestartsHealthyOperationMeasurement() {
        // The marker from a previous connection does not count toward the next one.
        StreamingRetryState state = streaming();
        state.recordFailure(true, 0);

        state.recordSuccess(10 * SECOND);
        state.recordFailure(false, 20 * SECOND); // healthy for 10 seconds only
        state.recordFailure(false, 90 * SECOND); // no message on this connection

        assertEquals(20 * MINUTE, state.nextDelayMillis());
    }

    @Test
    public void healthyOperationStartingAtTimeZeroCounts() {
        StreamingRetryState state = streaming();
        state.recordFailure(true, 0);
        state.recordSuccess(0);
        state.recordFailure(false, THRESHOLD);

        assertEquals(SECOND, state.nextDelayMillis());
    }

    // ---- configured initial reconnect delay ----

    @Test
    public void firstNormalRetryWaitsTheConfiguredInitialReconnectDelay() {
        StreamingRetryState state = new StreamingRetryState(250);
        state.recordFailure(false, 0);
        long wait = state.nextDelayMillis();
        assertTrue("wait " + wait, wait > 125 && wait <= 250);
    }

    @Test
    public void zeroInitialReconnectDelayReconnectsImmediatelyAfterNormalFailure() {
        StreamingRetryState state = new StreamingRetryState(0);
        state.recordFailure(false, 0);
        assertEquals(0, state.nextDelayMillis());

        state.recordFailure(true, 0);
        long wait = state.nextDelayMillis();
        assertTrue("wait " + wait, wait > RetryRegime.EXTENDED_INITIAL_DELAY_MILLIS / 2
                && wait <= RetryRegime.EXTENDED_INITIAL_DELAY_MILLIS);
    }

    @Test
    public void extendedRegimeIsNeverBelowTheInitialReconnectDelay() {
        StreamingRetryState state = new StreamingRetryState(10 * MINUTE);
        state.recordFailure(true, 0);
        long wait = state.nextDelayMillis();
        assertTrue("wait " + wait, wait > 5 * MINUTE && wait <= 10 * MINUTE);
    }
}
