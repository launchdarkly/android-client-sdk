package com.launchdarkly.sdk.android;

import com.google.gson.annotations.JsonAdapter;

/**
 * Container class representing a communication failure with LaunchDarkly servers in which the response was unexpected.
 */
@JsonAdapter(LDFailureSerialization.class)
public class LDInvalidResponseCodeFailure extends LDFailure {

    /**
     * The response code
     */
    private final int responseCode;

    /**
     * Whether or not the failure may be fixed by retrying
     */
    private final boolean retryable;

    /**
     * @param message the message
     * @param responseCode the response code
     * @param retryable whether or not retrying may resolve the issue
     */
    public LDInvalidResponseCodeFailure(String message, int responseCode, boolean retryable) {
        super(message, FailureType.UNEXPECTED_RESPONSE_CODE);
        this.responseCode = responseCode;
        this.retryable = retryable;
    }

    /**
     * @param message the message
     * @param cause the cause of the failure
     * @param responseCode the response code
     * @param retryable whether or not retrying may resolve the issue
     */
    public LDInvalidResponseCodeFailure(String message, Throwable cause, int responseCode, boolean retryable) {
        super(message, cause, FailureType.UNEXPECTED_RESPONSE_CODE);
        this.responseCode = responseCode;
        this.retryable = retryable;
    }

    /**
     * @return true if retrying may resolve the issue
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * @return the response code
     */
    public int getResponseCode() {
        return responseCode;
    }
}