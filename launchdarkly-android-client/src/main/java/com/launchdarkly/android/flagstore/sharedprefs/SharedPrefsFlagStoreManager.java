package com.launchdarkly.android.flagstore.sharedprefs;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.launchdarkly.android.FeatureFlagChangeListener;
import com.launchdarkly.android.flagstore.FlagStore;
import com.launchdarkly.android.flagstore.FlagStoreManager;
import com.launchdarkly.android.flagstore.FlagStoreUpdateType;
import com.launchdarkly.android.flagstore.StoreUpdatedListener;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

public class SharedPrefsFlagStoreManager implements FlagStoreManager, StoreUpdatedListener {

    private static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";
    private static final int MAX_USERS = 5;

    private final Application application;
    private String mobileKey;

    private final SharedPreferences usersSharedPrefs;
    private FlagStore currentFlagStore;

    private final Multimap<String, FeatureFlagChangeListener> listeners;

    public SharedPrefsFlagStoreManager(Application application, String mobileKey) {
        this.application = application;
        this.mobileKey = mobileKey;
        this.usersSharedPrefs = application.getSharedPreferences(SHARED_PREFS_BASE_KEY + mobileKey + "-users", Context.MODE_PRIVATE);
        HashMultimap<String, FeatureFlagChangeListener> multimap = HashMultimap.create();
        listeners = Multimaps.synchronizedMultimap(multimap);
    }

    @Override
    public void switchToUser(String userKey) {
        if (currentFlagStore != null) {
            currentFlagStore.unregisterOnStoreUpdatedListener();
        }
        currentFlagStore = new SharedPrefsFlagStore(application, sharedPrefsKeyForUser(userKey));
        currentFlagStore.registerOnStoreUpdatedListener(this);

        usersSharedPrefs.edit()
                .putLong(userKey, System.currentTimeMillis())
                .apply();

        while (usersSharedPrefs.getAll().size() > MAX_USERS) {
            List<String> allUsers = getAllUsers();
            String removed = allUsers.get(0);
            Timber.d("Exceeded max # of users: [%s] Removing user: [%s]", MAX_USERS, removed);
            deleteSharedPreferences(removed);
            usersSharedPrefs.edit()
                    .remove(removed)
                    .apply();
        }
    }

    /**
     * Completely deletes a user's saved flag settings and the remaining empty SharedPreferences xml file.
     *
     * @param userKey key the user's flag settings are stored under
     */
    @SuppressLint("ApplySharedPref")
    private void deleteSharedPreferences(String userKey) {
        SharedPreferences sharedPrefsToDelete = loadSharedPrefsForUser(userKey);
        sharedPrefsToDelete.edit()
                .clear()
                .commit();

        File file = new File(application.getFilesDir().getParent() + "/shared_prefs/" + sharedPrefsKeyForUser(userKey) + ".xml");
        Timber.i("Deleting SharedPrefs file:%s", file.getAbsolutePath());

        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private SharedPreferences loadSharedPrefsForUser(String userKey) {
        Timber.d("Using SharedPreferences key: [%s]", sharedPrefsKeyForUser(userKey));
        return application.getSharedPreferences(sharedPrefsKeyForUser(userKey), Context.MODE_PRIVATE);
    }

    private String sharedPrefsKeyForUser(String userKey) {
        return SHARED_PREFS_BASE_KEY + mobileKey + userKey + "-flags";
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
    private List<String> getAllUsers() {
        Map<String, ?> all = usersSharedPrefs.getAll();
        Map<String, Long> allTyped = new HashMap<>();
        //get typed versions of the users' timestamps:
        for (String k : all.keySet()) {
            try {
                allTyped.put(k, usersSharedPrefs.getLong(k, Long.MIN_VALUE));
                Timber.d("Found user: %s", userAndTimeStampToHumanReadableString(k, allTyped.get(k)));
            } catch (ClassCastException cce) {
                Timber.e(cce, "Unexpected type! This is not good");
            }
        }

        List<Map.Entry<String, Long>> sorted = new LinkedList<>(allTyped.entrySet());
        Collections.sort(sorted, new EntryComparator());
        List<String> results = new LinkedList<>();
        for (Map.Entry<String, Long> e : sorted) {
            Timber.d("Found sorted user: %s", userAndTimeStampToHumanReadableString(e.getKey(), e.getValue()));
            results.add(e.getKey());
        }
        return results;
    }

    private static String userAndTimeStampToHumanReadableString(String userSharedPrefsKey, Long timestamp) {
        return userSharedPrefsKey + " [" + userSharedPrefsKey + "] timestamp: [" + timestamp + "] [" + new Date(timestamp) + "]";
    }

    @Override
    public void onStoreUpdate(final String flagKey, final FlagStoreUpdateType flagStoreUpdateType) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            synchronized (listeners) {
                if (flagStoreUpdateType != FlagStoreUpdateType.FLAG_DELETED) {
                    for (FeatureFlagChangeListener listener : listeners.get(flagKey)) {
                        listener.onFeatureFlagChange(flagKey);
                    }
                } else {
                    listeners.removeAll(flagKey);
                }
            }
        } else {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    onStoreUpdate(flagKey, flagStoreUpdateType);
                }
            });
        }
    }

    class EntryComparator implements Comparator<Map.Entry<String, Long>> {
        @Override
        public int compare(Map.Entry<String, Long> lhs, Map.Entry<String, Long> rhs) {
            return (int) (lhs.getValue() - rhs.getValue());
        }
    }

    public Collection<FeatureFlagChangeListener> getListenersByKey(String key) {
        synchronized (listeners) {
            return listeners.get(key);
        }
    }
}
