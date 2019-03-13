package com.launchdarkly.android.flagstore;

/**
 * Listener interface for receiving FlagStore update callbacks
 */
public interface StoreUpdatedListener {
    /**
     * Called by a FlagStore when the store is updated.
     *
     * @param flagKey The key of the Flag that was updated
     * @param flagStoreUpdateType The type of update that occurred.
     */
    void onStoreUpdate(String flagKey, FlagStoreUpdateType flagStoreUpdateType);
}
