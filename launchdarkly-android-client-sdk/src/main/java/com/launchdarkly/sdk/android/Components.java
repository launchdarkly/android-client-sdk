package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;

/**
 * Provides configurable factories for the standard implementations of LaunchDarkly component interfaces.
 * <p>
 * Some of the configuration options in {@link LDConfig.Builder} affect the entire SDK, but others are
 * specific to one area of functionality, such as how the SDK receives feature flag updates or processes
 * analytics events. For the latter, the standard way to specify a configuration is to call one of the
 * static methods in {@link Components}, apply any desired configuration change to the object that that
 * method returns, and then use the corresponding method in {@link LDConfig.Builder} to use that
 * configured component in the SDK.
 *
 * @since 4.0.0
 */
public abstract class Components {
    private Components() {}

    /**
     * Returns a builder for configuring custom service URIs.
     * <p>
     * Passing this to {@link LDConfig.Builder#serviceEndpoints(com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder)},
     * after setting any desired properties on the builder, applies this configuration to the SDK.
     * <pre><code>
     *     LDConfig config = new LDConfig.Builder()
     *         .serviceEndpoints(
     *             Components.serviceEndpoints()
     *                 .relayProxy("http://my-relay-hostname:80")
     *         )
     *         .build();
     * </code></pre>
     *
     * @return a builder object
     * @see LDConfig.Builder#serviceEndpoints(com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder)
     */
    public static ServiceEndpointsBuilder serviceEndpoints() {
        return new ComponentsImpl.ServiceEndpointsBuilderImpl();
    }
}
