package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import timber.log.Timber;

class SharedPrefsFlagStoreManager implements FlagStoreManager, StoreUpdatedListener {

    private static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";

    @NonNull
    private final FlagStoreFactory flagStoreFactory;
    @NonNull
    private final String mobileKey;
    private final int maxCachedUsers;

    private FlagStore currentFlagStore;
    private final SharedPreferences usersSharedPrefs;
    private final ConcurrentHashMap<String, Set<FeatureFlagChangeListener>> listeners;
    private final CopyOnWriteArrayList<LDAllFlagsListener> allFlagsListeners;

    SharedPrefsFlagStoreManager(@NonNull Application application,
                                @NonNull String mobileKey,
                                @NonNull FlagStoreFactory flagStoreFactory,
                                int maxCachedUsers) {
        this.mobileKey = mobileKey;
        this.flagStoreFactory = flagStoreFactory;
        this.maxCachedUsers = maxCachedUsers;
        this.usersSharedPrefs = application.getSharedPreferences(SHARED_PREFS_BASE_KEY + mobileKey + "-users", Context.MODE_PRIVATE);
        this.listeners = new ConcurrentHashMap<>();
        this.allFlagsListeners = new CopyOnWriteArrayList<>();
    }

    @Override
    public void switchToUser(String userKey) {
        String storeId = storeIdentifierForUser(userKey);
        if (currentFlagStore != null) {
            currentFlagStore.unregisterOnStoreUpdatedListener();
        }
        currentFlagStore = flagStoreFactory.createFlagStore(storeId);
        currentFlagStore.registerOnStoreUpdatedListener(this);

        // Store the user's key and the current time in usersSharedPrefs so it can be removed when
        // MAX_USERS is exceeded.
        usersSharedPrefs.edit()
                .putLong(userKey, System.currentTimeMillis())
                .apply();

        int usersStored = usersSharedPrefs.getAll().size();
        // Negative numbers represent an unlimited number of cached users. The active user is not
        // considered a cached user, so we subtract one.
        int usersToRemove = maxCachedUsers >= 0 ? usersStored - maxCachedUsers - 1 : 0;
        if (usersToRemove > 0) {
            Iterator<String> oldestFirstUsers = getCachedUsers(storeId).iterator();
            // Remove oldest users until we are at MAX_USERS.
            for (int i = 0; i < usersToRemove; i++) {
                String removed = oldestFirstUsers.next();
                Timber.d("Exceeded max # of users: [%s] Removing user: [%s]", maxCachedUsers, removed);
                // Load FlagStore for oldest user and delete it.
                flagStoreFactory.createFlagStore(storeIdentifierForUser(removed)).delete();
                // Remove entry from usersSharedPrefs.
                usersSharedPrefs.edit().remove(removed).apply();
            }
        }
    }

    private String storeIdentifierForUser(String userKey) {
        return mobileKey + userKey;
    }

    @Override
    public FlagStore getCurrentUserStore() {
        return currentFlagStore;
    }

    @Override
    public void registerListener(String key, FeatureFlagChangeListener listener) {
        Map<FeatureFlagChangeListener, Boolean> backingMap = new ConcurrentHashMap<>();
        Set<FeatureFlagChangeListener> newSet = Collections.newSetFromMap(backingMap);
        newSet.add(listener);
        Set<FeatureFlagChangeListener> oldSet = listeners.putIfAbsent(key, newSet);
        if (oldSet != null) {
            oldSet.add(listener);
            Timber.d("Added listener. Total count: [%s]", oldSet.size());
        } else {
            Timber.d("Added listener. Total count: 1");
        }
    }

    @Override
    public void unRegisterListener(String key, FeatureFlagChangeListener listener) {
        Set<FeatureFlagChangeListener> keySet = listeners.get(key);
        if (keySet != null) {
            boolean removed = keySet.remove(listener);
            if (removed) {
                Timber.d("Removing listener for key: [%s]", key);
            }
        }
    }

    @Override
    public void registerAllFlagsListener(LDAllFlagsListener listener) {
        allFlagsListeners.add(listener);
    }

    @Override
    public void unregisterAllFlagsListener(LDAllFlagsListener listener) {
        allFlagsListeners.remove(listener);
    }

    // Gets cached users (does not include the active user) sorted by creation time (oldest first)
    private Collection<String> getCachedUsers(String activeUser) {
        Map<String, ?> all = usersSharedPrefs.getAll();
        all.remove(activeUser);
        TreeMap<Long, String> sortedMap = new TreeMap<>();
        //get typed versions of the users' timestamps and insert into sorted TreeMap
        for (String k : all.keySet()) {
            try {
                sortedMap.put((Long) all.get(k), k);
                Timber.d("Found user: %s", userAndTimeStampToHumanReadableString(k, (Long) all.get(k)));
            } catch (ClassCastException cce) {
                Timber.e(cce, "Unexpected type! This is not good");
            }
        }
        return sortedMap.values();
    }

    private static String userAndTimeStampToHumanReadableString(String userSharedPrefsKey, Long timestamp) {
        return userSharedPrefsKey + " [" + userSharedPrefsKey + "] timestamp: [" + timestamp + "]" + " [" + new Date(timestamp) + "]";
    }

    private void dispatchStoreUpdateCallback(final String flagKey, final FlagStoreUpdateType flagStoreUpdateType) {
        // We make sure to call listener callbacks on the main thread, as we consistently did so in
        // the past by virtue of using SharedPreferences to implement the callbacks.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Get the listeners for the specific key
            Set<FeatureFlagChangeListener> keySet = listeners.get(flagKey);
            // If there are any listeners for this key
            if (keySet != null) {
                // We only call the listener if the flag is a new flag or updated.
                if (flagStoreUpdateType != FlagStoreUpdateType.FLAG_DELETED) {
                    for (FeatureFlagChangeListener listener : keySet) {
                        listener.onFeatureFlagChange(flagKey);
                    }
                } else {
                    // When flag is deleted we remove the corresponding listeners
                    listeners.remove(flagKey);
                }
            }
        } else {
            // Call ourselves on the main thread
            new Handler(Looper.getMainLooper()).post(() -> {
                dispatchStoreUpdateCallback(flagKey, flagStoreUpdateType);
            });
        }
    }

    @Override
    public void onStoreUpdate(List<Pair<String, FlagStoreUpdateType>> updates) {
        List<String> flagKeys = new ArrayList<>();
        for (Pair<String, FlagStoreUpdateType> update : updates) {
            flagKeys.add(update.first);
            dispatchStoreUpdateCallback(update.first, update.second);
        }
        for (LDAllFlagsListener allFlagsListener : allFlagsListeners) {
            allFlagsListener.onChange(flagKeys);
        }
    }

    public Collection<FeatureFlagChangeListener> getListenersByKey(String key) {
        Set<FeatureFlagChangeListener> res = listeners.get(key);
        return res == null ? new HashSet<FeatureFlagChangeListener>() : res;
    }
}
