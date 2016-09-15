package com.launchdarkly.android;

/**
 * Callback interface used for listening to changes to a feature flag.
 */
public interface FeatureFlagChangeListener {
    void onFeatureFlagChange(String flagKey);
}
