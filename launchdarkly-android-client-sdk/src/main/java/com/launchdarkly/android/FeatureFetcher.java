package com.launchdarkly.android;

import com.google.gson.JsonObject;

interface FeatureFetcher {
    void fetch(LDUser user, final LDUtil.ResultCallback<JsonObject> callback);
}
