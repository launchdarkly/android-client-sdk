package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;

/**
 * Extension of the data source update sink that supports applying changesets
 * and exposing the current selector.
 * <p>
 * Extends {@link TransactionalDataSourceUpdateSink} with the ability to read the
 * current selector (stored in memory from the last applied changeset) for use on
 * the next request (e.g. as basis).
 * <p>
 * Status reporting continues to use {@link DataSourceUpdateSink#setStatus} and
 * {@link DataSourceUpdateSink#shutDown()} from the base {@link DataSourceUpdateSink};
 * there is no updateStatus or getDataStoreStatusProvider on this interface.
 *
 * @see DataSourceUpdateSink
 * @see TransactionalDataSourceUpdateSink
 * @see ChangeSet
 */
public interface DataSourceUpdateSinkV2 extends TransactionalDataSourceUpdateSink {
    /**
     * Returns the current selector from the last applied changeset that carried one.
     * Stored in memory only (not persisted). After process restart this is effectively empty.
     *
     * @return the current selector, or {@link Selector#EMPTY} if none has been stored
     */
    @NonNull
    Selector getSelector();
}
