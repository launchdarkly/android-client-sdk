package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.LDConfig.Builder;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;

/**
 * Contains methods for configuring the polling data source.
 * <p>
 * Polling is not the default behavior; by default, the SDK uses a streaming connection to receive
 * feature flag data from LaunchDarkly whenever the application is in the foreground. In polling
 * mode, the SDK instead makes a new HTTP request to LaunchDarkly at regular intervals. HTTP
 * caching allows it to avoid redundantly downloading data if there have been no changes, but
 * polling is still less efficient than streaming and should only be used on the advice of
 * LaunchDarkly support.
 * <p>
 * To use polling mode, create a builder with {@link Components#pollingDataSource()}, set any custom
 * options if desired with the methods of this class, and pass it to
 * {@link Builder#dataSource(ComponentConfigurer)}:
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder()
 *         .dataSource(Components.pollingDataSource().pollIntervalMillis(30000))
 *         .build();
 * </code></pre>
 * <p>
 * Note that this class is abstract; the actual implementation is created by calling
 * {@link Components#pollingDataSource()}.
 *
 * @since 3.3.0
 */
public abstract class PollingDataSourceBuilder implements ComponentConfigurer<DataSource> {
    /**
     * The default value for {@link #pollIntervalMillis(int)}: 5 minutes.
     */
    public static final int DEFAULT_POLL_INTERVAL_MILLIS = 300_000;

    /**
     * The background polling interval in millis
     */
    protected int backgroundPollIntervalMillis = LDConfig.DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS;

    /**
     * The foreground polling interval in millis
     */
    protected int pollIntervalMillis = DEFAULT_POLL_INTERVAL_MILLIS;

    /**
     * Sets the interval between feature flag updates when the application is running in the background.
     * <p>
     * This is normally a longer interval than the foreground polling interval ({@link #pollIntervalMillis(int)}).
     * It is ignored if you have turned off background polling entirely by setting
     * {@link Builder#disableBackgroundUpdating(boolean)}.
     * <p>
     * The default value is {@link LDConfig#DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS}; the minimum
     * is {@link LDConfig#MIN_BACKGROUND_POLL_INTERVAL_MILLIS}.
     *
     * @param backgroundPollIntervalMillis the background polling interval in milliseconds
     * @return the builder
     * @see #pollIntervalMillis(int)
     */
    public PollingDataSourceBuilder backgroundPollIntervalMillis(int backgroundPollIntervalMillis) {
        this.backgroundPollIntervalMillis = backgroundPollIntervalMillis < LDConfig.MIN_BACKGROUND_POLL_INTERVAL_MILLIS ?
                LDConfig.MIN_BACKGROUND_POLL_INTERVAL_MILLIS : backgroundPollIntervalMillis;
        return this;
    }

    /**
     * Sets the interval between feature flag updates when the application is running in the foreground.
     * <p>
     * The default value is {@link #DEFAULT_POLL_INTERVAL_MILLIS}. That is also the minimum value.
     *
     * @param pollIntervalMillis the reconnect time base value in milliseconds
     * @return the builder
     * @see #backgroundPollIntervalMillis(int)
     */
    public PollingDataSourceBuilder pollIntervalMillis(int pollIntervalMillis) {
        this.pollIntervalMillis = pollIntervalMillis <= DEFAULT_POLL_INTERVAL_MILLIS ?
                DEFAULT_POLL_INTERVAL_MILLIS : pollIntervalMillis;
        return this;
    }
}
