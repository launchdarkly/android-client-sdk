package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.subsystems.HookConfiguration;
import com.launchdarkly.sdk.android.subsystems.PluginsConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public abstract class PluginsConfigurationBuilder {
    protected List<Plugin> plugins = Collections.emptyList();

    public PluginsConfigurationBuilder setPlugins(List<Plugin> plugins) {
        // copy to avoid list manipulations impacting the SDK
        this.plugins = Collections.unmodifiableList(new ArrayList<>(plugins));
        return this;
    }

    abstract public PluginsConfiguration build();
}