package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;

import java.io.Closeable;

interface FeatureFetcher extends Closeable {
    void fetch(LDContext context, final Callback<String> callback);
}
