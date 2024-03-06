package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.google.gson.annotations.JsonAdapter;

/**
 * Container class representing a communication failure with LaunchDarkly servers.
 */
@JsonAdapter(LDFailureSerialization.class)
public class LDFailure extends LaunchDarklyException {

    /**
     * Enumerated type defining the possible values of {@link LDFailure#getFailureType()}.
     */
    public enum FailureType {
        /**
         * A response body received either through polling or streaming was unable to be parsed.
         */
        INVALID_RESPONSE_BODY,

        /**
         * A network request for polling, or the EventSource stream reported a failure.
         */
        NETWORK_FAILURE,

        /**
         *  An event was received through the stream with an unknown event key. This could indicate a newer SDK is
         *  available if new event kinds have become available through the flag stream since the SDK's release.
         */
        UNEXPECTED_STREAM_ELEMENT_TYPE,

        /**
         * This indicates the LDFailure is an instance of LDInvalidResponseCodeFailure.
         */
        UNEXPECTED_RESPONSE_CODE,

        /**
         * Some other issue occurred.
         */
        UNKNOWN_ERROR
    }

    /**
     * The failure type
     */
    @NonNull
    private final FailureType failureType;

    /**
     * @param message the message
     * @param failureType the failure type
     */
    public LDFailure(String message, @NonNull FailureType failureType) {
        super(message);
        this.failureType = failureType;
    }

    /**
     * @param message the message
     * @param cause the cause of the failure
     * @param failureType the failure type
     */
    public LDFailure(String message, Throwable cause, @NonNull FailureType failureType) {
        super(message, cause);
        this.failureType = failureType;
    }

    /**
     * @return the failure type
     */
    @NonNull
    public FailureType getFailureType() {
        return failureType;
    }
}