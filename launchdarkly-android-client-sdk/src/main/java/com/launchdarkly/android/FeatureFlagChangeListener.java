package com.launchdarkly.android;

/**
 * Callback interface used for listening to changes to a feature flag.
 *
 * @see LDClientInterface#registerFeatureFlagListener(String, FeatureFlagChangeListener)
 */
@FunctionalInterface
public interface FeatureFlagChangeListener {
    /**
     * The SDK calls this method when a feature flag value has changed for the current user.
     * <p>
     * To obtain the new value, call one of the client methods such as {@link LDClientInterface#boolVariation(String, boolean)}.
     *
     * @param flagKey the feature flag key
     */
    void onFeatureFlagChange(String flagKey);
}
