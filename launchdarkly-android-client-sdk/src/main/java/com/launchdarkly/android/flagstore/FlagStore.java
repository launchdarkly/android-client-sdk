package com.launchdarkly.android.flagstore;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

/**
 * A FlagStore supports getting individual or collections of flag updates and updating an underlying
 * persistent store. Individual flags can be retrieved by a flagKey, or all flags retrieved. Allows
 * replacing backing store for flags at a future date, as well as mocking for unit testing.
 */
public interface FlagStore {

    /**
     * Delete the backing persistent store for this identifier entirely. Further operations on a
     * FlagStore are undefined after calling this method.
     */
    void delete();

    /**
     * Remove all flags from the store.
     */
    void clear();

    /**
     * Returns true if a flag with the key is in the store, otherwise false.
     *
     * @param key The key to check for membership in the store.
     * @return Whether a flag with the given key is in the store.
     */
    boolean containsKey(String key);

    /**
     * Get an individual flag from the store. If a flag with the key flagKey is not stored, returns
     * null.
     *
     * @param flagKey The key to get the corresponding flag for.
     * @return The flag with the key flagKey or null.
     */
    @Nullable
    Flag getFlag(String flagKey);

    /**
     * Apply an individual flag update to the FlagStore.
     *
     * @param flagUpdate The FlagUpdate to apply.
     */
    void applyFlagUpdate(FlagUpdate flagUpdate);

    /**
     * Apply a list of flag updates to the FlagStore.
     *
     * @param flagUpdates The list of FlagUpdates to apply.
     */
    void applyFlagUpdates(List<? extends FlagUpdate> flagUpdates);

    /**
     * First removes all flags from the store, then applies a list of flag updates to the
     * FlagStore.
     *
     * @param flagUpdates The list of FlagUpdates to apply.
     */
    void clearAndApplyFlagUpdates(List<? extends FlagUpdate> flagUpdates);

    /**
     * Gets all flags currently in the store.
     *
     * @return A collection of the stored Flags.
     */
    Collection<Flag> getAllFlags();

    /**
     * Register a listener to be called on any updates to the store. If a listener is already
     * registered, it will be replaced with the argument listener. The FlagStore implementation is
     * not guaranteed to retain a strong reference to the listener.
     *
     * @param storeUpdatedListener The listener to be called on store updates.
     */
    void registerOnStoreUpdatedListener(StoreUpdatedListener storeUpdatedListener);

    /**
     * Remove the currently registered listener if one exists.
     */
    void unregisterOnStoreUpdatedListener();
}
