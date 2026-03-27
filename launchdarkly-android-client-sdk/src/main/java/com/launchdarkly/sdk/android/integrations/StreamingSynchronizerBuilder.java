package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

/**
 * Contains methods for configuring the streaming synchronizer.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * A streaming synchronizer maintains a persistent connection to LaunchDarkly's
 * Flag Delivery service and receives real-time feature flag updates. It is
 * typically the primary synchronizer for the foreground streaming mode.
 * <p>
 * Obtain an instance from {@link com.launchdarkly.sdk.android.DataSystemComponents#streamingSynchronizer()},
 * configure it, and pass it to
 * {@link ConnectionModeBuilder#synchronizers(DataSourceBuilder[])}:
 * <pre><code>
 *     DataSystemComponents.customMode()
 *         .synchronizers(
 *             DataSystemComponents.streamingSynchronizer()
 *                 .initialReconnectDelayMillis(500))
 * </code></pre>
 * <p>
 * Note that this class is abstract; the actual implementation is created by calling
 * {@link com.launchdarkly.sdk.android.DataSystemComponents#streamingSynchronizer()}.
 *
 * @see com.launchdarkly.sdk.android.DataSystemComponents
 * @see ConnectionModeBuilder
 */
public abstract class StreamingSynchronizerBuilder implements DataSourceBuilder<Synchronizer> {

    /**
     * The default value for {@link #initialReconnectDelayMillis(int)}: 1000 milliseconds.
     */
    public static final int DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS = 1_000;

    /**
     * The initial reconnection delay in milliseconds.
     */
    protected int initialReconnectDelayMillis = DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS;

    /**
     * Per-source service endpoint override, or null to use the SDK-level endpoints.
     */
    protected ServiceEndpoints serviceEndpointsOverride;

    /**
     * Sets the initial reconnect delay for the streaming connection.
     * <p>
     * The streaming service uses a backoff algorithm (with jitter) every time the
     * connection needs to be reestablished. The delay for the first reconnection will
     * start near this value, and then increase exponentially for any subsequent
     * connection failures.
     * <p>
     * The default value is {@link #DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS}.
     *
     * @param initialReconnectDelayMillis the reconnect time base value in milliseconds
     * @return this builder
     */
    public StreamingSynchronizerBuilder initialReconnectDelayMillis(int initialReconnectDelayMillis) {
        this.initialReconnectDelayMillis = initialReconnectDelayMillis <= 0
                ? DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS
                : initialReconnectDelayMillis;
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
    public StreamingSynchronizerBuilder serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsBuilder) {
        this.serviceEndpointsOverride = serviceEndpointsBuilder.createServiceEndpoints();
        return this;
    }
}
