package com.launchdarkly.android;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonObject;

public interface FeatureFlagFetcher {
    ListenableFuture<JsonObject> fetch(LDUser user);

    void setOffline();

    void setOnline();
}
