package com.launchdarkly.sdk.android;

/**
 * Listener for various SDK state changes.
 */
public interface LDStatusListener {

    /**
     * Invoked when the connection mode changes
     * @param connectionInformation the connection information that gives details about the connection
     */
    void onConnectionModeChanged(ConnectionInformation connectionInformation);

    /**
     * Invoked when an internal issue results in a failure to connect to LaunchDarkly
     * @param ldFailure the failure
     */
    void onInternalFailure(LDFailure ldFailure);

}
