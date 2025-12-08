package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.LDClient;

import java.util.Collections;
import java.util.List;

/**
 * Abstract class that you can extend to create a plugin to the LaunchDarkly SDK.
 */
public abstract class Plugin {

    /**
     * @return the {@link PluginMetadata} that gives details about the plugin.
     */
    @NonNull
    public abstract PluginMetadata getMetadata();

    /**
     * Registers the plugin with the SDK. Called once during SDK initialization.
     * The SDK initialization will typically not have been completed at this point, so the plugin should take appropriate
     * actions to ensure the SDK is ready before sending track events or evaluating flags.
     * Implementations should be prepared for this method to be invoked multiple times in case
     * the SDK is configured with multiple environments. Use the metadata to distinguish
     * environments.
     *
     * @param client   for the plugin to use
     * @param metadata metadata about the environment where the plugin is running.
     */
    public abstract void register(LDClient client, EnvironmentMetadata metadata);

    /**
     * Gets a list of hooks that the plugin wants to register.
     * This method will be called once during SDK initialization before the register method is called.
     * If the plugin does not need to register any hooks, this method doesn't need to be implemented.
     * Implementations should be prepared for this method to be invoked multiple times in case
     * the SDK is configured with multiple environments. Use the metadata to distinguish
     * environments.
     *
     * @param metadata metadata about the environment where the plugin is running.
     * @return a non-null, possibly empty, list of {@link Hook} instances
     */
    @NonNull
    public List<Hook> getHooks(EnvironmentMetadata metadata) {
        // default impl
        return Collections.emptyList();
    }

    public void onPluginsReady(RegistrationCompleteResult result, EnvironmentMetadata metadata) {
        // default: do nothing
    }
}
