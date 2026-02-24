package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;

/**
 * Interface that an FDv2 {@link DataSource} will use to push data into the SDK.
 * <p>
 * This interface extends {@link TransactionalDataSourceUpdateSink} to add
 * status tracking and status update capabilities required for FDv2 data sources.
 */
public interface DataSourceUpdateSinkV2 extends TransactionalDataSourceUpdateSink {

    /**
     * Informs the SDK of a change in the data source's status.
     *
     * @param state   the data source state
     * @param failure if non-null, represents an error/exception that caused the status change
     */
    void setStatus(@NonNull DataSourceState state, @Nullable Throwable failure);

    /**
     * Informs the SDK that the data source is being permanently shut down due to an
     * unrecoverable problem reported by LaunchDarkly, such as the mobile key being invalid.
     * <p>
     * This implies that the SDK should also stop other components that communicate
     * with LaunchDarkly, such as the event processor. It also changes the connection
     * mode to
     * {@link com.launchdarkly.sdk.android.ConnectionInformation.ConnectionMode#SHUTDOWN}.
     */
    void shutDown();
}
