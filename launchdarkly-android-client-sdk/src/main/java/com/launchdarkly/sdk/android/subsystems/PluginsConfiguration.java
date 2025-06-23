package com.launchdarkly.sdk.android.subsystems;

import com.launchdarkly.sdk.android.integrations.Hook;
import com.launchdarkly.sdk.android.integrations.Plugin;

import java.util.Collections;
import java.util.List;

/**
 * Encapsulates the SDK's 'plugins' configuration.
 * <p>
 * Use {@link com.launchdarkly.sdk.android.integrations.PluginsConfigurationBuilder} to construct an instance.
 */
public class PluginsConfiguration {

    private final List<Plugin> plugins;

    /**
     * @param plugins the list of {@link Plugin} that will be registered.
     */
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
