package com.launchdarkly.sdk.android.subsystems;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.LDConfig.Builder;

/**
 * An object that describes how the SDK will obtain feature flag data from LaunchDarkly.
 * <p>
 * Currently, this is a simple container for configuration properties. In the future, it will become
 * a real component interface allowing for custom behavior, as it is in the server-side Java SDK.
 *
 * @since 3.3.0
 * @see Components#streamingDataSource()
 * @see Components#pollingDataSource()
 * @see LDConfig.Builder#dataSource(ComponentConfigurer)
 */
public interface DataSource {
    /**
     * Returns true if streaming is disabled.
     * @return true if streaming is disabled
     */
    boolean isStreamingDisabled();

    /**
     * Returns the configured background polling interval.
     * @return the background polling interval in milliseconds
     */
    int getBackgroundPollIntervalMillis();

    /**
     * Returns the configured initial stream reconnect delay.
     * @return the initial stream reconnect delay in milliseconds, or zero if streaming is disabled
     */
    int getInitialReconnectDelayMillis();

    /**
     * Returns the configured foreground polling interval.
     * @return the foreground polling interval in milliseconds, or zero if streaming is enabled
     */
    int getPollIntervalMillis();

    /**
     * Returns the option set by
     * {@link com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder#streamEvenInBackground(boolean)}.
     * @return true if streaming mode can continue in the background (if not disabled)
     * @since 3.4.0
     */
    default boolean isStreamEvenInBackground() {
        return false;
    }
}
