package com.launchdarkly.sdk.android.subsystems;

/**
 * Data source status state, aligned with java-core's {@code DataSourceStatusProvider.State}.
 * Used internally by FDv2 data sources and the sink to communicate connection health;
 * the sink maps this to {@link com.launchdarkly.sdk.android.ConnectionInformation.ConnectionMode}
 * for the public API.
 */
public enum DataSourceState {
    /**
     * The initial state of the data source when the SDK is being initialized.
     * <p>
     * If it encounters an error that requires it to retry initialization, the state will remain at
     * {@link #INITIALIZING} until it either succeeds and becomes {@link #VALID}, or permanently fails and
     * becomes {@link #OFF}.
     */
    INITIALIZING,

    /**
     * Indicates that the data source is currently operational and has not had any problems since the
     * last time it received data.
     * <p>
     * In streaming mode, this means that there is currently an open stream connection and that at least
     * one initial message has been received on the stream. In polling mode, it means that the last poll
     * request succeeded.
     */
    VALID,

    /**
     * Indicates that the data source encountered an error that it will attempt to recover from.
     * <p>
     * In streaming mode, this means that the stream connection failed, or had to be dropped due to some
     * other error, and will be retried after a backoff delay. In polling mode, it means that the last poll
     * request failed, and a new poll request will be made after the configured polling interval.
     */
    INTERRUPTED,

    /**
     * Indicates that the data source has been permanently shut down.
     * <p>
     * This could be because it encountered an unrecoverable error (for instance, the LaunchDarkly service
     * rejected the SDK key; an invalid SDK key will never become valid), or because the SDK client was
     * explicitly shut down, or because all initializers and synchronizers have been exhausted.
     */
    OFF
}
