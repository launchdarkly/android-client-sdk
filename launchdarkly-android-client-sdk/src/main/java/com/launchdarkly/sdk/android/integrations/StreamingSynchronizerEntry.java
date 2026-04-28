package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;

import androidx.annotation.Nullable;

/**
 * Entry for the FDv2 streaming synchronizer.
 * <p>
 * Early access; not stable.
 */
public final class StreamingSynchronizerEntry extends SynchronizerEntry {

    /**
     * Default initial reconnect delay (milliseconds) for the streaming synchronizer.
     */
    public static final int DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS = 1_000;

    private int initialReconnectDelayMillis = DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS;

    @Nullable
    private ServiceEndpoints serviceEndpointsOverride;

    public StreamingSynchronizerEntry() {
    }

    public StreamingSynchronizerEntry initialReconnectDelayMillis(int initialReconnectDelayMillis) {
        this.initialReconnectDelayMillis = initialReconnectDelayMillis <= 0
                ? DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS
                : initialReconnectDelayMillis;
        return this;
    }

    public StreamingSynchronizerEntry serviceEndpointsOverride(@Nullable ServiceEndpoints endpoints) {
        this.serviceEndpointsOverride = endpoints;
        return this;
    }

    public StreamingSynchronizerEntry serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsBuilder) {
        this.serviceEndpointsOverride = serviceEndpointsBuilder.createServiceEndpoints();
        return this;
    }

    public int getInitialReconnectDelayMillis() {
        return initialReconnectDelayMillis;
    }

    @Nullable
    public ServiceEndpoints getServiceEndpointsOverride() {
        return serviceEndpointsOverride;
    }
}
