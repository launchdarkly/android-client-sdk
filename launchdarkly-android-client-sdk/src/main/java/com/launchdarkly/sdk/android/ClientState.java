package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.subsystems.ClientContext;

/**
 * Internal interface for components to get information about the state of the SDK client instance
 * they belong to. This is different from {@link ClientContext} because it describes state that can
 * change.
 * <p>
 * For most SDK components, this state is read-only, so the interface includes only getters. Any
 * SDK components that are allowed to change the state will do so via the implementation class,
 * {@link ClientStateImpl}.
 */
interface ClientState {
    /**
     * Returns true if the SDK client is currently set to be offline (regardless of whether the
     * network is available).
     * @return true if set to be offline
     */
    boolean isForcedOffline();
}
