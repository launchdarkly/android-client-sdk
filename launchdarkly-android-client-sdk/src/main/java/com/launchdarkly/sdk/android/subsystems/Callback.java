package com.launchdarkly.sdk.android.subsystems;

/**
 * General-purpose interface for callbacks that can succeed or fail.
 * @param <T> the return value type
 * @since 4.0.0
 */
public interface Callback<T> {
    /**
     * This method is called on successful completion.
     * @param result the return value
     */
    void onSuccess(T result);

    /**
     * This method is called on failure.
     * @param error the error/exception object
     */
    void onError(Throwable error);
}
