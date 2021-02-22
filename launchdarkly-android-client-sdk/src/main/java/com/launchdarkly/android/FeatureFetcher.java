package com.launchdarkly.android;

import com.google.gson.JsonObject;
import com.launchdarkly.sdk.LDUser;

interface FeatureFetcher {
    void fetch(LDUser user, final LDUtil.ResultCallback<JsonObject> callback);
}
