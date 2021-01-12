package com.launchdarkly.android;

import androidx.annotation.NonNull;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.JsonAdapter;

@JsonAdapter(LDFailureSerialization.class)
public class LDFailure extends LaunchDarklyException {

    /**
     * Enumerated type defining the possible values of {@link LDFailure#getFailureType()}.
     */
    public enum FailureType {
        INVALID_RESPONSE_BODY,
        NETWORK_FAILURE,
        UNEXPECTED_STREAM_ELEMENT_TYPE,
        UNEXPECTED_RESPONSE_CODE,
        UNKNOWN_ERROR
    }

    @Expose
    private FailureType failureType;

    public LDFailure(String message,
              @NonNull FailureType failureType) {
        super(message);
        this.failureType = failureType;
    }

    public LDFailure(String message, Throwable cause,
              @NonNull FailureType failureType) {
        super(message, cause);
        this.failureType = failureType;
    }

    public FailureType getFailureType() {
        return failureType;
    }
}