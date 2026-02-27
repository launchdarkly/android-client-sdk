package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.DataModel;
import com.launchdarkly.sdk.fdv2.ChangeSet;

import java.util.Map;

/**
 * Interface that an implementation of {@link DataSource} will use to push data into the SDK transactionally.
 * <p>
 * The data source interacts with this object, rather than manipulating the store directly, so
 * that the SDK can perform any other necessary operations that must happen when data is updated.
 * <p>
 * Component factories for {@link DataSource} implementations receive a sink that implements
 * this interface (and {@link DataSourceUpdateSink}) via {@link ClientContext#getDataSourceUpdateSink()}.
 *
 * @see DataSource
 * @see ClientContext
 * @see DataSourceUpdateSink
 * @see DataSourceUpdateSinkV2
 */
public interface TransactionalDataSourceUpdateSink {
    /**
     * Apply the given change set to the store. This should be done atomically if possible.
     * The context is the one used by the data source to obtain the changeset; the store
     * will not modify data if that context is no longer the active context.
     *
     * @param context   the context that was used to get the changeset (must still be active for apply to succeed)
     * @param changeSet the changeset to apply
     */
    void apply(@NonNull LDContext context, @NonNull ChangeSet<Map<String, DataModel.Flag>> changeSet);
}
