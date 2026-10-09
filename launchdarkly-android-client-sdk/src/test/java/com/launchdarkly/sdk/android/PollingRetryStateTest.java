package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PollingRetryStateTest {
    private static final long POLL_INTERVAL_MS = 1_000;
    private static final long EXTENDED_INITIAL_DELAY_MS = 300_000;

    @Test
    public void ordinaryFailuresKeepPollInterval() {
        Throwable[] ordinaryFailures = {
                new LDFailure("network error", LDFailure.FailureType.NETWORK_FAILURE),
                new LDInvalidResponseCodeFailure("bad request", 400, true),
                new LDInvalidResponseCodeFailure("request timeout", 408, true),
                new LDInvalidResponseCodeFailure("too many requests", 429, true),
                new LDInvalidResponseCodeFailure("server error", 503, true),
        };

        for (Throwable failure : ordinaryFailures) {
            PollingRetryState state = new PollingRetryState(POLL_INTERVAL_MS);

            state.recordFailure(failure);

            // A poll interval is already a wait, so an ordinary failure does not extend it.
            assertEquals(failure.toString(), POLL_INTERVAL_MS, state.nextDelayMillis());
        }
    }

    @Test
    public void unexpectedStatusesExtendTheWait() {
        int[] unexpectedStatuses = {401, 403, 404, 405};

        for (int status : unexpectedStatuses) {
            PollingRetryState state = new PollingRetryState(POLL_INTERVAL_MS);

            state.recordFailure(new LDInvalidResponseCodeFailure("status " + status, status, true));

            // The wait becomes minutes long. Jitter can halve a delay.
            assertTrue("status " + status,
                    state.nextDelayMillis() >= EXTENDED_INITIAL_DELAY_MS / 2);
        }
    }

    @Test
    public void extendedWaitNeverDropsBelowPollInterval() {
        // This poll interval is longer than the extended delay.
        PollingRetryState state = new PollingRetryState(3_600_000);

        state.recordFailure(new LDInvalidResponseCodeFailure("unauthorized", 401, true));

        // A failing source never polls more often than a healthy one.
        assertEquals(3_600_000, state.nextDelayMillis());
    }

    @Test
    public void successReturnsToPollIntervalImmediately() {
        PollingRetryState state = new PollingRetryState(POLL_INTERVAL_MS);
        state.recordFailure(new LDInvalidResponseCodeFailure("unauthorized", 401, true));

        // The wait is extended before the success.
        assertTrue(state.nextDelayMillis() >= EXTENDED_INITIAL_DELAY_MS / 2);

        // One poll succeeds.
        state.recordSuccess();

        // A success shows that the service works, so polling resumes at its interval.
        assertEquals(POLL_INTERVAL_MS, state.nextDelayMillis());
    }

    @Test
    public void oneSuccessDoesNotEndTheExtendedRegime() {
        PollingRetryState state = new PollingRetryState(POLL_INTERVAL_MS);
        state.recordFailure(new LDInvalidResponseCodeFailure("unauthorized", 401, true));

        // One poll succeeds, then the next one fails for an ordinary reason.
        state.recordSuccess();
        state.recordFailure(new LDFailure("network error", LDFailure.FailureType.NETWORK_FAILURE));

        // One success is not enough to leave the extended regime.
        assertTrue(state.nextDelayMillis() >= EXTENDED_INITIAL_DELAY_MS / 2);
    }

    @Test
    public void twoSuccessesEndTheExtendedRegime() {
        PollingRetryState state = new PollingRetryState(POLL_INTERVAL_MS);
        state.recordFailure(new LDInvalidResponseCodeFailure("unauthorized", 401, true));

        // Two polls succeed, then the next one fails for an ordinary reason.
        state.recordSuccess();
        state.recordSuccess();
        state.recordFailure(new LDFailure("network error", LDFailure.FailureType.NETWORK_FAILURE));

        // Enough successes end the extended regime, so the wait is the poll interval again.
        assertEquals(POLL_INTERVAL_MS, state.nextDelayMillis());
    }
}
