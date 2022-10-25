package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;

/**
 * Internal interface for components to get information about the SDK client instance they belong
 * to. This includes both things that are set at initialization time, like the mobile key, and
 * things that can change dynamically, like the "are we set to be explicitly offline" state.
 * <p>
 * For most SDK components, this state is read-only, so the interface includes only getters. Any
 * SDK components that are allowed to change the state will do so via the implementation class,
 * {@link ClientStateImpl}.
 */
interface ClientState {
    /**
     * Returns the configured mobile key.
     * @return the mobile key
     */
    String getMobileKey();

    /**
     * Returns the configured environment name.
     * @return the environment name
     */
    String getEnvironmentName();

    /**
     * Returns the configured logger.
     * @return the logger
     */
    LDLogger getLogger();

    /**
     * Returns true if the SDK client is currently set to be offline (regardless of whether the
     * network is available).
     * @return true if set to be offline
     */
    boolean isForcedOffline();
}
