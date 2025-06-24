package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.subsystems.PluginsConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Contains methods for configuring the SDK's 'plugins'.
 * <p>
 * If you want to add plugins, use {@link Components#plugins()}, configure accordingly, and pass it
 * to {@link com.launchdarkly.sdk.android.LDConfig.Builder#plugins(PluginsConfigurationBuilder)}.
 *
 * <pre><code>
 *     List plugins = getPluginsFunc();
 *     LDConfig config = new LDConfig.Builder()
 *         .plugins(
 *             Components.plugins()
 *                 .setPlugins(plugins)
 *         )
 *         .build();
 * </code></pre>
 * <p>
 * Note that this class is abstract; the actual implementation is created by calling {@link Components#plugins()}.
 */
public abstract class PluginsConfigurationBuilder {

    /**
     * The current set of plugins the builder has.
     */
    protected List<Plugin> plugins = Collections.emptyList();

    /**
     * Sets the provided list of plugins on the configuration.  Note that the order of plugins is important and controls
     * the order in which they will be registered.  See {@link Plugin} for more details.
     *
     * @param plugins to be set on the configuration
     * @return the builder
     */
    public PluginsConfigurationBuilder setPlugins(List<Plugin> plugins) {
        // copy to avoid list manipulations impacting the SDK
        this.plugins = Collections.unmodifiableList(new ArrayList<>(plugins));
        return this;
    }

    /**
     * @return the plugins configuration
     */
    abstract public PluginsConfiguration build();
}