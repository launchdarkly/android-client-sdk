package com.launchdarkly.android;

public interface LDStatusListener {

    void onConnectionModeChanged(ConnectionInformation connectionInformation);
    void onInternalFailure(LDFailure ldFailure);

}
