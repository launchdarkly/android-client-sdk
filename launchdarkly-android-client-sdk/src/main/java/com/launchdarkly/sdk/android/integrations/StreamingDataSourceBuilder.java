package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.LDConfig.Builder;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;

/**
 * Contains methods for configuring the streaming data source.
 * <p>
 * By default, the SDK uses a streaming connection to receive feature flag data from LaunchDarkly. If you want
 * to customize the behavior of the connection, create a builder with {@link Components#streamingDataSource()},
 * change its properties with the methods of this class, and pass it to {@link Builder#dataSource(ComponentConfigurer)}:
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder()
 *         .dataSource(Components.streamingDataSource().initialReconnectDelayMillis(500))
 *         .build();
 * </code></pre>
 * <p>
 * Note that this class is abstract; the actual implementation is created by calling {@link Components#streamingDataSource()}.
 *
 * @since 3.3.0
 */
public abstract class StreamingDataSourceBuilder implements ComponentConfigurer<DataSource> {
    /**
     * The default value for {@link #initialReconnectDelayMillis(int)}: 1000 milliseconds.
     */
    public static final int DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS = 1_000;

    protected int backgroundPollIntervalMillis = LDConfig.DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS;
    protected int initialReconnectDelayMillis = DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS;
    protected boolean streamEvenInBackground = false;

    /**
     * Sets the interval between feature flag updates when the application is running in the background.
     * <p>
     * Even when configured to use streaming, the SDK will switch to polling when in the background
     * (unless {@link Builder#disableBackgroundUpdating(boolean)} is set). This property determines
     * how often polling will happen.
     * <p>
     * The default value is {@link LDConfig#DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS}; the minimum
     * is {@link LDConfig#MIN_BACKGROUND_POLL_INTERVAL_MILLIS}.
     *
     * @param backgroundPollIntervalMillis the reconnect time base value in milliseconds
     * @return the builder
     */
    public StreamingDataSourceBuilder backgroundPollIntervalMillis(int backgroundPollIntervalMillis) {
        this.backgroundPollIntervalMillis = backgroundPollIntervalMillis < LDConfig.MIN_BACKGROUND_POLL_INTERVAL_MILLIS ?
                LDConfig.MIN_BACKGROUND_POLL_INTERVAL_MILLIS : backgroundPollIntervalMillis;
        return this;
    }

    /**
     * Sets the initial reconnect delay for the streaming connection.
     * <p>
     * The streaming service uses a backoff algorithm (with jitter) every time the connection needs
     * to be reestablished. The delay for the first reconnection will start near this value, and then
     * increase exponentially for any subsequent connection failures.
     * <p>
     * The default value is {@link #DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS}.
     *
     * @param initialReconnectDelayMillis the reconnect time base value in milliseconds
     * @return the builder
     */
    public StreamingDataSourceBuilder initialReconnectDelayMillis(int initialReconnectDelayMillis) {
        this.initialReconnectDelayMillis = initialReconnectDelayMillis <= 0 ? 0 :
                initialReconnectDelayMillis;
        return this;
    }

    /**
     * Sets whether streaming should be used even if the application is in the background.
     * <p>
     * By default, this option is false, meaning that if the application is in the background then
     * the SDK will turn off the stream connection and use infrequent polling, until the application
     * is in the foreground again.
     * <p>
     * If you set this option to {@code true}, the SDK will continue to use the stream connection
     * regardless of foreground/background state. Use this option with caution, since normally it is
     * preferable to limit network usage by backgrounded applications.
     *
     * @param streamEvenInBackground true if streaming should be used even in the background
     * @return the builder
     * @since 3.4.0
     */
    public StreamingDataSourceBuilder streamEvenInBackground(boolean streamEvenInBackground) {
        this.streamEvenInBackground = streamEvenInBackground;
        return this;
    }
}
