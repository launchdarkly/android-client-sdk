package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.Initializer;

/**
 * Contains methods for configuring the polling initializer.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * A polling initializer makes a single poll request to retrieve the initial set
 * of feature flag data. It is typically used as the first step in a connection mode's
 * data pipeline.
 * <p>
 * Obtain an instance from {@link com.launchdarkly.sdk.android.DataSystemComponents#pollingInitializer()},
 * configure it, and pass it to
 * {@link ConnectionModeBuilder#initializers(ComponentConfigurer[])}:
 * <pre><code>
 *     DataSystemComponents.customMode()
 *         .initializers(DataSystemComponents.pollingInitializer())
 * </code></pre>
 * <p>
 * Note that this class is abstract; the actual implementation is created by calling
 * {@link com.launchdarkly.sdk.android.DataSystemComponents#pollingInitializer()}.
 *
 * @see com.launchdarkly.sdk.android.DataSystemComponents
 * @see ConnectionModeBuilder
 */
public abstract class PollingInitializerBuilder implements ComponentConfigurer<Initializer> {

    /**
     * Per-source service endpoint override, or null to use the SDK-level endpoints.
     */
    protected ServiceEndpoints serviceEndpointsOverride;

    /**
     * Sets overrides for the service endpoints used by this initializer.
     * <p>
     * In typical usage, the initializer uses the service endpoints configured at the
     * SDK level via
     * {@link com.launchdarkly.sdk.android.LDConfig.Builder#serviceEndpoints(ServiceEndpointsBuilder)}.
     * Use this method only when you need a specific initializer to connect to different
     * endpoints than the rest of the SDK.
     *
     * @param serviceEndpointsBuilder the service endpoints override
     * @return this builder
     */
    public PollingInitializerBuilder serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsBuilder) {
        this.serviceEndpointsOverride = serviceEndpointsBuilder.createServiceEndpoints();
        return this;
    }
}
