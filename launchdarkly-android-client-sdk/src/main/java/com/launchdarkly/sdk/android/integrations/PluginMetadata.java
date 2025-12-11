package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;

public abstract class PluginMetadata {

    @NonNull
    public String getId() {
        return "";
    }

    @NonNull
    public abstract String getName();

    @NonNull
    public String getVersion() {
        return "";
    }
}
