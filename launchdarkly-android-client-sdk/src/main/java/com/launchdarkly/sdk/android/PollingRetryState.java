package com.launchdarkly.sdk.android;

import androidx.annotation.Nullable;

import com.launchdarkly.eventsource.RetryDelayStrategy;

import java.util.concurrent.TimeUnit;

/**
 * Computes the wait before a polling data source polls again.
 * <p>
 * A poll interval is already a wait, so an ordinary failure does not extend it. Only a failure
 * that is unlikely to correct itself, such as a rejected credential, moves polling into a
 * regime that waits longer, and it stays there until enough consecutive polls succeed.
 * <p>
 * This class is not thread safe.
 */
final class PollingRetryState {
    private static final int RESET_THRESHOLD_SUCCESSES = 2;
    private static final long EXTENDED_INITIAL_DELAY_MS = 300_000;
    private static final long EXTENDED_MAX_DELAY_MS = 3_600_000;

    private final long pollIntervalMillis;

    private RetryDelayStrategy extendedDelay;
    private boolean inExtendedRegime;
    private int consecutiveSuccesses;

    PollingRetryState(long pollIntervalMillis) {
        this.pollIntervalMillis = pollIntervalMillis;
        this.extendedDelay = freshExtendedDelay();
    }

    // Neither bound drops below the poll interval, which would make a failing source poll more
    // often than a healthy one.
    private RetryDelayStrategy freshExtendedDelay() {
        return RetryDelayStrategy.defaultStrategy()
                .initialDelay(Math.max(EXTENDED_INITIAL_DELAY_MS, pollIntervalMillis),
                        TimeUnit.MILLISECONDS)
                .maxDelay(Math.max(EXTENDED_MAX_DELAY_MS, pollIntervalMillis),
                        TimeUnit.MILLISECONDS);
    }

    void recordFailure(@Nullable Throwable error) {
        consecutiveSuccesses = 0;
        if (!inExtendedRegime) {
            if (isUnexpected(error)) {
                inExtendedRegime = true;
                extendedDelay = freshExtendedDelay();
            }
            return;
        }
        extendedDelay = extendedDelay.getNext();
    }

    // True for an HTTP status the service is unlikely to stop returning, such as a 401.
    private static boolean isUnexpected(@Nullable Throwable error) {
        return error instanceof LDInvalidResponseCodeFailure
                && !LDUtil.isHttpErrorRecoverable(
                        ((LDInvalidResponseCodeFailure) error).getResponseCode());
    }

    void recordSuccess() {
        consecutiveSuccesses++;
        if (consecutiveSuccesses >= RESET_THRESHOLD_SUCCESSES) {
            inExtendedRegime = false;
            extendedDelay = freshExtendedDelay();
        }
    }

    long nextDelayMillis() {
        // A success shows the service works, so polling returns to its interval at once even
        // though the regime has not reset yet.
        if (!inExtendedRegime || consecutiveSuccesses > 0) {
            return pollIntervalMillis;
        }
        return Math.max(pollIntervalMillis, extendedDelay.getDelayMillis());
    }
}
