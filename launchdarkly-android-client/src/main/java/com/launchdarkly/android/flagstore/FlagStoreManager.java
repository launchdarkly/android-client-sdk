package com.launchdarkly.android.flagstore;

import com.launchdarkly.android.FeatureFlagChangeListener;

import java.util.Collection;

/**
 * A FlagStoreManager is responsible for managing FlagStores for active and recently active users,
 * as well as providing flagKey specific update callbacks.
 */
public interface FlagStoreManager {

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
     * Gets all the listeners currently registered to the given key.
     *
     * @param key The key to return the listeners for.
     * @return A collection of listeners registered to the key.
     */
    Collection<FeatureFlagChangeListener> getListenersByKey(String key);
}
