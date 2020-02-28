package com.launchdarkly.android;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;

import java.util.Collection;
import java.util.List;

/**
 * A FlagStore supports getting individual or collections of flag updates and updating an underlying
 * persistent store. Individual flags can be retrieved by a flagKey, or all flags retrieved. Allows
 * replacing backing store for flags at a future date, as well as mocking for unit testing.
 */
interface FlagStore {

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

/**
 * Types of updates that a FlagStore can report
 */
enum FlagStoreUpdateType {
    /**
     * The flag was deleted
     */
    FLAG_DELETED,
    /**
     * The flag has been updated or replaced
     */
    FLAG_UPDATED,
    /**
     * A new flag has been created
     */
    FLAG_CREATED
}

/**
 * Listener interface for receiving FlagStore update callbacks
 */
interface StoreUpdatedListener {
    /**
     * Called by a FlagStore when the store is updated.
     *
     * @param updates Pairs of flag keys that were updated and the type of update that occurred.
     */
    void onStoreUpdate(List<Pair<String, FlagStoreUpdateType>> updates);
}

/**
 * This interface is used to provide a mechanism for a FlagStoreManager to create FlagStores without
 * being dependent on a concrete FlagStore class.
 */
interface FlagStoreFactory {

    /**
     * Create a new flag store
     *
     * @param identifier identifier to associate all flags under
     * @return A new instance of a FlagStore backed by a concrete implementation.
     */
    FlagStore createFlagStore(@NonNull String identifier);

}

/**
 * A FlagStoreManager is responsible for managing FlagStores for active and recently active users,
 * as well as providing flagKey specific update callbacks.
 */
interface FlagStoreManager {

    /**
     * Loads the FlagStore for the particular userKey. If too many users have a locally cached
     * FlagStore, deletes the oldest.
     *
     * @param userKey The key representing the user to switch to
     */
    void switchToUser(String userKey);

    /**
     * Gets the current user's flag store.
     *
     * @return The flag store for the current user.
     */
    FlagStore getCurrentUserStore();

    /**
     * Register a listener to be called when a flag with the given key is created or updated.
     * Multiple listeners can be registered to a single key.
     *
     * @param key      Flag key to register the listener to.
     * @param listener The listener to be called when the flag is updated.
     */
    void registerListener(String key, FeatureFlagChangeListener listener);

    /**
     * Unregister a specific listener registered to the given key.
     *
     * @param key      Flag key to unregister the listener from.
     * @param listener The specific listener to be unregistered.
     */
    void unRegisterListener(String key, FeatureFlagChangeListener listener);

    /**
     * Register a listener to be called whenever new flag data is received.
     *
     * @param listener The listener to be called new flag data is received.
     */
    void registerAllFlagsListener(LDAllFlagsListener listener);

    /**
     * Unregister a listener previously registered with registerAllFlagsListener.
     *
     * @param listener The specific listener to be unregistered.
     */
    void unregisterAllFlagsListener(LDAllFlagsListener listener);

    /**
     * Gets all the listeners currently registered to the given key.
     *
     * @param key The key to return the listeners for.
     * @return A collection of listeners registered to the key.
     */
    Collection<FeatureFlagChangeListener> getListenersByKey(String key);
}