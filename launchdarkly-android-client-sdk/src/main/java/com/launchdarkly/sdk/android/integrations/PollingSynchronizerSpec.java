package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;

import androidx.annotation.Nullable;

/**
 * Spec for the FDv2 polling synchronizer.
 * <p>
 * Early access; not stable.
 */
public final class PollingSynchronizerSpec extends SynchronizerSpec {

    private int pollIntervalMillis = LDConfig.DEFAULT_POLL_INTERVAL_MILLIS;

    @Nullable
    private ServiceEndpoints serviceEndpointsOverride;

    public PollingSynchronizerSpec() {
    }

    public PollingSynchronizerSpec pollIntervalMillis(int pollIntervalMillis) {
        this.pollIntervalMillis = Math.max(pollIntervalMillis, LDConfig.DEFAULT_POLL_INTERVAL_MILLIS);
        return this;
    }

    public PollingSynchronizerSpec serviceEndpointsOverride(@Nullable ServiceEndpoints endpoints) {
        this.serviceEndpointsOverride = endpoints;
        return this;
    }

    public PollingSynchronizerSpec serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsBuilder) {
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
