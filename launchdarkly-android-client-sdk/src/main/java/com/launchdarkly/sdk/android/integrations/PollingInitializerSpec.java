package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;

import androidx.annotation.Nullable;

/**
 * Spec for the FDv2 polling initializer.
 * <p>
 * Early access; not stable.
 */
public final class PollingInitializerSpec extends InitializerSpec {

    @Nullable
    private ServiceEndpoints serviceEndpointsOverride;

    public PollingInitializerSpec() {
    }

    /**
     * Overrides service endpoints for this initializer, or {@code null} for SDK defaults.
     */
    public PollingInitializerSpec serviceEndpointsOverride(@Nullable ServiceEndpoints endpoints) {
        this.serviceEndpointsOverride = endpoints;
        return this;
    }

    /**
     * Overrides service endpoints for this initializer using a builder.
     */
    public PollingInitializerSpec serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsBuilder) {
        this.serviceEndpointsOverride = serviceEndpointsBuilder.createServiceEndpoints();
        return this;
    }

    @Nullable
    public ServiceEndpoints getServiceEndpointsOverride() {
        return serviceEndpointsOverride;
    }
}
