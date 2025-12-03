package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;

public abstract class PluginMetadata {

    @NonNull
    public abstract String getName();

    @NonNull
    public abstract String getVersion();
}
