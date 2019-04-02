package com.launchdarkly.android.flagstore.sharedprefs;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.launchdarkly.android.FeatureFlagChangeListener;
import com.launchdarkly.android.flagstore.FlagStore;
import com.launchdarkly.android.flagstore.FlagStoreFactory;
import com.launchdarkly.android.flagstore.FlagStoreManager;
import com.launchdarkly.android.flagstore.FlagStoreUpdateType;
import com.launchdarkly.android.flagstore.StoreUpdatedListener;

import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import timber.log.Timber;

public class SharedPrefsFlagStoreManager implements FlagStoreManager, StoreUpdatedListener {

    private static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";
    private static final int MAX_USERS = 5;

    @NonNull
    private final FlagStoreFactory flagStoreFactory;
    @NonNull
    private String mobileKey;

    private FlagStore currentFlagStore;
    private final SharedPreferences usersSharedPrefs;
    private final Multimap<String, FeatureFlagChangeListener> listeners;

    public SharedPrefsFlagStoreManager(@NonNull Application application,
                                       @NonNull String mobileKey,
                                       @NonNull FlagStoreFactory flagStoreFactory) {
        this.mobileKey = mobileKey;
        this.flagStoreFactory = flagStoreFactory;
        this.usersSharedPrefs = application.getSharedPreferences(SHARED_PREFS_BASE_KEY + mobileKey + "-users", Context.MODE_PRIVATE);
        HashMultimap<String, FeatureFlagChangeListener> multimap = HashMultimap.create();
        listeners = Multimaps.synchronizedMultimap(multimap);
    }

    @Override
    public void switchToUser(String userKey) {
        if (currentFlagStore != null) {
            currentFlagStore.unregisterOnStoreUpdatedListener();
        }
        currentFlagStore = flagStoreFactory.createFlagStore(storeIdentifierForUser(userKey));
        currentFlagStore.registerOnStoreUpdatedListener(this);

        // Store the user's key and the current time in usersSharedPrefs so it can be removed when
        // MAX_USERS is exceeded.
        usersSharedPrefs.edit()
                .putLong(userKey, System.currentTimeMillis())
                .apply();

        int usersStored = usersSharedPrefs.getAll().size();
        if (usersStored > MAX_USERS) {
            Iterator<String> oldestFirstUsers = getAllUsers().iterator();
            // Remove oldest users until we are at MAX_USERS.
            while (usersStored-- > MAX_USERS) {
                String removed = oldestFirstUsers.next();
                Timber.d("Exceeded max # of users: [%s] Removing user: [%s]", MAX_USERS, removed);
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
        synchronized (listeners) {
            listeners.put(key, listener);
            Timber.d("Added listener. Total count: [%s]", listeners.size());
        }
    }

    @Override
    public void unRegisterListener(String key, FeatureFlagChangeListener listener) {
        synchronized (listeners) {
            Iterator<FeatureFlagChangeListener> it = listeners.get(key).iterator();
            while (it.hasNext()) {
                FeatureFlagChangeListener check = it.next();
                if (check.equals(listener)) {
                    Timber.d("Removing listener for key: [%s]", key);
                    it.remove();
                }
            }
        }
    }

    // Gets all users sorted by creation time (oldest first)
    private Collection<String> getAllUsers() {
        Map<String, ?> all = usersSharedPrefs.getAll();
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

    @Override
    public void onStoreUpdate(final String flagKey, final FlagStoreUpdateType flagStoreUpdateType) {
        // We make sure to call listener callbacks on the main thread, as we consistently did so in
        // the past by virtue of using SharedPreferences to implement the callbacks.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Make sure listeners are not updated while we are calling them.
            synchronized (listeners) {
                // We only call the listener if the flag is a new flag or updated.
                if (flagStoreUpdateType != FlagStoreUpdateType.FLAG_DELETED) {
                    for (FeatureFlagChangeListener listener : listeners.get(flagKey)) {
                        listener.onFeatureFlagChange(flagKey);
                    }
                } else {
                    // When flag is deleted we remove the corresponding listeners
                    listeners.removeAll(flagKey);
                }
            }
        } else {
            // Call ourselves on the main thread
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    onStoreUpdate(flagKey, flagStoreUpdateType);
                }
            });
        }
    }

    public Collection<FeatureFlagChangeListener> getListenersByKey(String key) {
        synchronized (listeners) {
            return listeners.get(key);
        }
    }
}
