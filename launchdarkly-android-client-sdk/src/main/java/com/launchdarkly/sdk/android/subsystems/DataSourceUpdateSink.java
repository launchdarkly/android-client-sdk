package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.ConnectionInformation;
import com.launchdarkly.sdk.android.DataModel;

import java.util.Map;

/**
 * Interface that an implementation of {@link DataSource} will use to push data into the SDK.
 *
 * @since 4.0.0
 */
public interface DataSourceUpdateSink {
    /**
     * Completely overwrites the current contents of the data store with a new set of items.
     *
     * @param items a map of flag keys to flag evaluation results
     */
    void init(@NonNull Map<String, DataModel.Flag> items);

    /**
     * Updates or inserts an item. If an item already exists with the same key, the operation will
     * only succeed if the existing version is less than the new version.
     * <p>
     * If a flag has been deleted, the data source should pass a versioned placeholder created with
     * {@link DataModel.Flag#deletedItemPlaceholder(String, int)}.
     *
     * @param item the new evaluation result data (or a deleted item placeholder)
     */
    void upsert(@NonNull DataModel.Flag item);

    /**
     * Informs the SDK of a change in the data source's status or the connection mode.
     *
     * @param connectionMode the value that should be reported by
     *                       {@link ConnectionInformation#getConnectionMode()}
     * @param failure if non-null, represents an error/exception that caused data source
     *                initialization to fail
     */
    void setStatus(@NonNull ConnectionInformation.ConnectionMode connectionMode, @Nullable Throwable failure);

    /**
     * Informs the SDK that the data source is being permanently shut down due to an unrecoverable
     * problem reported by LaunchDarkly, such as the mobile key being invalid.
     * <p>
     * This implies that the SDK should also stop other components that communicate with
     * LaunchDarkly, such as the event processor. It also changes the connection mode to
     * {@link com.launchdarkly.sdk.android.ConnectionInformation.ConnectionMode#SHUTDOWN}.
     */
    void shutDown();
}

