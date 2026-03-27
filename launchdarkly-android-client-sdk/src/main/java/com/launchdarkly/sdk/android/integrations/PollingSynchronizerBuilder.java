package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

/**
 * Contains methods for configuring the polling synchronizer.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * A polling synchronizer periodically polls LaunchDarkly for feature flag updates.
 * It can be used as a primary synchronizer or as a fallback when the streaming
 * connection is unavailable.
 * <p>
 * Obtain an instance from {@link com.launchdarkly.sdk.android.DataSystemComponents#pollingSynchronizer()},
 * configure it, and pass it to
 * {@link ConnectionModeBuilder#synchronizers(DataSourceBuilder[])}:
 * <pre><code>
 *     DataSystemComponents.customMode()
 *         .synchronizers(
 *             DataSystemComponents.pollingSynchronizer()
 *                 .pollIntervalMillis(60_000))
 * </code></pre>
 * <p>
 * Note that this class is abstract; the actual implementation is created by calling
 * {@link com.launchdarkly.sdk.android.DataSystemComponents#pollingSynchronizer()}.
 *
 * @see com.launchdarkly.sdk.android.DataSystemComponents
 * @see ConnectionModeBuilder
 */
public abstract class PollingSynchronizerBuilder implements DataSourceBuilder<Synchronizer> {

    /**
     * The default value for {@link #pollIntervalMillis(int)}: 5 minutes (300,000 ms).
     */
    public static final int DEFAULT_POLL_INTERVAL_MILLIS = 300_000;

    /**
     * The polling interval in milliseconds.
     */
    protected int pollIntervalMillis = DEFAULT_POLL_INTERVAL_MILLIS;

    /**
     * Per-source service endpoint override, or null to use the SDK-level endpoints.
     */
    protected ServiceEndpoints serviceEndpointsOverride;

    /**
     * Sets the interval at which the SDK will poll for feature flag updates.
     * <p>
     * The default and minimum value is {@link #DEFAULT_POLL_INTERVAL_MILLIS}. Values
     * less than this will be set to the default.
     *
     * @param pollIntervalMillis the polling interval in milliseconds
     * @return this builder
     */
    public PollingSynchronizerBuilder pollIntervalMillis(int pollIntervalMillis) {
        this.pollIntervalMillis = Math.max(pollIntervalMillis, DEFAULT_POLL_INTERVAL_MILLIS);
        return this;
    }

    /**
     * Sets overrides for the service endpoints used by this synchronizer.
     * <p>
     * In typical usage, the synchronizer uses the service endpoints configured at the
     * SDK level via
     * {@link com.launchdarkly.sdk.android.LDConfig.Builder#serviceEndpoints(ServiceEndpointsBuilder)}.
     * Use this method only when you need a specific synchronizer to connect to different
     * endpoints than the rest of the SDK.
     *
     * @param serviceEndpointsBuilder the service endpoints override
     * @return this builder
     */
    public PollingSynchronizerBuilder serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsBuilder) {
        this.serviceEndpointsOverride = serviceEndpointsBuilder.createServiceEndpoints();
        return this;
    }
}
