package com.launchdarkly.android;

import com.google.gson.JsonObject;

public interface FeatureFlagFetcher {
    void fetch(LDUser user, final Util.ResultCallback<JsonObject> callback);
}
