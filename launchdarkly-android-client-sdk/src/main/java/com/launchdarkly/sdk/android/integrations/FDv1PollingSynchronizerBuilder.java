package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

/**
 * Contains methods for configuring the FDv1 polling synchronizer used as a fallback
 * when the server signals that FDv2 endpoints are unavailable.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * Obtain an instance from {@link com.launchdarkly.sdk.android.DataSystemComponents#fdv1PollingSynchronizer()}.
 * <p>
 * Note that this class is abstract; the actual implementation is created by calling
 * {@link com.launchdarkly.sdk.android.DataSystemComponents#fdv1PollingSynchronizer()}.
 *
 * @see com.launchdarkly.sdk.android.DataSystemComponents
 */
public abstract class FDv1PollingSynchronizerBuilder implements DataSourceBuilder<Synchronizer> {

    /**
     * The polling interval in milliseconds.
     */
    protected int pollIntervalMillis = PollingSynchronizerBuilder.DEFAULT_POLL_INTERVAL_MILLIS;

    /**
     * Sets the interval at which the FDv1 fallback synchronizer will poll for feature flag updates.
     *
     * @param pollIntervalMillis the polling interval in milliseconds
     * @return this builder
     */
    public FDv1PollingSynchronizerBuilder pollIntervalMillis(int pollIntervalMillis) {
        this.pollIntervalMillis = Math.max(pollIntervalMillis,
                PollingSynchronizerBuilder.DEFAULT_POLL_INTERVAL_MILLIS);
        return this;
    }
}
