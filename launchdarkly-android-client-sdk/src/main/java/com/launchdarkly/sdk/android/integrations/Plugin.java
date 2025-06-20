package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.LDClient;
import java.util.Collections;
import java.util.List;

public abstract class Plugin {

    @NonNull
    public abstract PluginMetadata getMetadata();

    public abstract void register(LDClient client, EnvironmentMetadata metadata);

    @NonNull
    public List<Hook> getHooks() {
        // default impl
        return Collections.emptyList();
    }
}
