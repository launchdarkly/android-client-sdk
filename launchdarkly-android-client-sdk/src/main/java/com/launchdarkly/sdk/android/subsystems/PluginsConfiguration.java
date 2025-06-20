package com.launchdarkly.sdk.android.subsystems;

import com.launchdarkly.sdk.android.integrations.Plugin;

import java.util.Collections;
import java.util.List;


public class PluginsConfiguration {

    private final List<Plugin> plugins;


    public PluginsConfiguration(List<Plugin> plugins) {
        this.plugins = Collections.unmodifiableList(plugins);
    }

    /**
     * @return immutable list of plugins
     */
    public List<Plugin> getPlugins() {
        return plugins;
    }
}
