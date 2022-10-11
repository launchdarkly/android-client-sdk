package com.launchdarkly.sdk.android;

import com.google.gson.JsonObject;
import com.launchdarkly.sdk.LDContext;

interface FeatureFetcher {
    void fetch(LDContext context, final LDUtil.ResultCallback<String> callback);
}
