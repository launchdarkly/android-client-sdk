package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;

import androidx.annotation.Nullable;

/**
 * Entry for the FDv2 polling initializer.
 * <p>
 * Early access; not stable.
 */
public final class PollingInitializerEntry extends InitializerEntry {

    @Nullable
    private ServiceEndpoints serviceEndpointsOverride;

    public PollingInitializerEntry() {
    }

    /**
     * Overrides service endpoints for this initializer, or {@code null} for SDK defaults.
     */
    public PollingInitializerEntry serviceEndpointsOverride(@Nullable ServiceEndpoints endpoints) {
        this.serviceEndpointsOverride = endpoints;
        return this;
    }

    /**
     * Overrides service endpoints for this initializer using a builder.
     */
    public PollingInitializerEntry serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsBuilder) {
        this.serviceEndpointsOverride = serviceEndpointsBuilder.createServiceEndpoints();
        return this;
    }

    @Nullable
    public ServiceEndpoints getServiceEndpointsOverride() {
        return serviceEndpointsOverride;
    }
}
