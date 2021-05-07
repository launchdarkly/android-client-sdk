package com.launchdarkly.sdk.android;

public interface LDStatusListener {

    void onConnectionModeChanged(ConnectionInformation connectionInformation);
    void onInternalFailure(LDFailure ldFailure);

}
