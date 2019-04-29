package com.launchdarkly.android;

/**
 * Exception class that can be thrown by LaunchDarkly client methods.
 */
public class LaunchDarklyException extends Exception {
    public LaunchDarklyException(String message) {
        super(message);
    }

    LaunchDarklyException(String message, Throwable cause) {
        super(message, cause);
    }
}