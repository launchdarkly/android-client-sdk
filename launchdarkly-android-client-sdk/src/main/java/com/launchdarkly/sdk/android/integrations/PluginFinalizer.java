package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.LDClient;

import java.util.List;

/**
 * Optional lifecycle hook for plugins that need to perform work after all plugins have been registered.
 * This is invoked once per environment after {@link Plugin#register(LDClient, EnvironmentMetadata)} has
 * been called on every plugin.
 */
public interface PluginFinalizer {
    void onPluginsReady(List<Plugin> allPlugins, EnvironmentMetadata metadata, LDClient client);
}
