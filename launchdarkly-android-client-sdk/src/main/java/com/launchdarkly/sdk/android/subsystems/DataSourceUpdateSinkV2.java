package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.ConnectionInformation;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;

/**
 * Interfaces required by data source updates implementations in FDv2.
 * <p>
 * This interface extends {@link TransactionalDataSourceUpdateSink} to add
 * status tracking and status update capabilities required for FDv2 data sources.
 */
public interface DataSourceUpdateSinkV2 extends TransactionalDataSourceUpdateSink {

    /**
     * Informs the SDK of a change in the data source's status or the connection
     * mode.
     *
     * @param connectionMode the value that should be reported by
     *                       {@link ConnectionInformation#getConnectionMode()}
     * @param failure        if non-null, represents an error/exception that caused
     *                       data source
     *                       initialization to fail
     */
    void setStatus(@NonNull ConnectionInformation.ConnectionMode connectionMode, @Nullable Throwable failure);

    /**
     * Informs the SDK that the data source is being permanently shut down due to an
     * unrecoverable
     * problem reported by LaunchDarkly, such as the mobile key being invalid.
     * <p>
     * This implies that the SDK should also stop other components that communicate
     * with
     * LaunchDarkly, such as the event processor. It also changes the connection
     * mode to
     * {@link com.launchdarkly.sdk.android.ConnectionInformation.ConnectionMode#SHUTDOWN}.
     */
    void shutDown();
}
