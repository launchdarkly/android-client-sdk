package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;

/**
 * Interface for a data store that holds feature flags and related data received by the SDK.
 * This interface supports updating the store transactionally using {@link ChangeSet}s.
 * <p>
 * Ordinarily, the only implementation of this interface is the default in-memory
 * implementation, which holds references to actual SDK data model objects.
 * <p>
 * Implementations must be thread-safe.
 *
 * @see TransactionalDataSourceUpdateSink
 */
public interface TransactionalDataStore {

    /**
     * Apply the given change set to the store. This should be done atomically if possible.
     * Implementations may ignore the update if the given context is no longer the active context.
     *
     * @param context   the context that was used to obtain the changeset (e.g. for staleness checks)
     * @param changeSet the changeset to apply
     */
    void apply(@NonNull LDContext context, @NonNull ChangeSet changeSet);

    /**
     * Returns the selector for the currently stored data. The selector will be non-null but may be empty.
     *
     * @return the selector for the currently stored data
     */
    @NonNull
    Selector getSelector();
}
