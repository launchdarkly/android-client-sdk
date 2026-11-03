package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;

import androidx.annotation.Nullable;

/**
 * Entry for the FDv1 fallback synchronizer of a connection mode.
 * <p>
 * The SDK switches a mode to this FDv1 polling synchronizer when LaunchDarkly directs it
 * to fall back to FDv1. It is not one of the mode's synchronizers; set it with
 * {@link ConnectionModeBuilder#fdv1FallbackSynchronizer(FDv1FallbackSynchronizerEntry)}.
 * <p>
 * Early access; not stable.
 */
public final class FDv1FallbackSynchronizerEntry {

    private int pollIntervalMillis = LDConfig.DEFAULT_POLL_INTERVAL_MILLIS;

    @Nullable
    private ServiceEndpoints serviceEndpointsOverride;

    public FDv1FallbackSynchronizerEntry() {
    }

    /**
     * Sets the poll interval of the FDv1 fallback synchronizer. The default and the minimum
     * are {@link LDConfig#DEFAULT_POLL_INTERVAL_MILLIS}.
     *
     * @param pollIntervalMillis the poll interval in milliseconds
     * @return this entry
     */
    public FDv1FallbackSynchronizerEntry pollIntervalMillis(int pollIntervalMillis) {
        this.pollIntervalMillis = Math.max(pollIntervalMillis, LDConfig.DEFAULT_POLL_INTERVAL_MILLIS);
        return this;
    }

    /**
     * Overrides service endpoints for the FDv1 fallback synchronizer, or {@code null} to use
     * the SDK's polling endpoint.
     *
     * @param endpoints the service endpoints, or null
     * @return this entry
     */
    public FDv1FallbackSynchronizerEntry serviceEndpointsOverride(@Nullable ServiceEndpoints endpoints) {
        this.serviceEndpointsOverride = endpoints;
        return this;
    }

    /**
     * Overrides service endpoints for the FDv1 fallback synchronizer using a builder.
     *
     * @param serviceEndpointsBuilder the service endpoints builder
     * @return this entry
     */
    public FDv1FallbackSynchronizerEntry serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsBuilder) {
        this.serviceEndpointsOverride = serviceEndpointsBuilder.createServiceEndpoints();
        return this;
    }

    public int getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    @Nullable
    public ServiceEndpoints getServiceEndpointsOverride() {
        return serviceEndpointsOverride;
    }
}
