package com.launchdarkly.android;


import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.util.Log;
import android.util.Pair;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Persists and retrieves feature flag values for different {@link LDUser}s.
 * Also enables realtime updates via registering a {@link FeatureFlagChangeListener}
 * with a feature flag.
 */
class UserManager {
    private static final String TAG = "LDUserManager";
    private static final int MAX_USERS = 5;

    private final String sharedPrefsBaseKey;

    // The active user is the one that we track for changes to enable listeners.
    // Its values will mirror the current user, but it is a different SharedPreferences
    // than the current user so we can attach OnSharedPreferenceChangeListeners to it.
    private final SharedPreferences activeUserSharedPrefs;

    // Keeps track of the 5 most recent current users
    private final SharedPreferences usersSharedPrefs;

    private final Application application;

    // Maintains references enabling (de)registration of listeners for realtime updates
    private final Multimap<String, Pair<FeatureFlagChangeListener, OnSharedPreferenceChangeListener>> listeners = ArrayListMultimap.create();

    // The current user- we'll always update this user from the response we get from the api
    private SharedPreferences currentUserSharedPrefs;
    private LDUser currentUser;

    UserManager(Application application, LDUser user) {
        this.application = application;
        this.sharedPrefsBaseKey = "LaunchDarkly-" + application.getPackageName();
        this.usersSharedPrefs = application.getSharedPreferences(sharedPrefsBaseKey + "-users", Context.MODE_PRIVATE);
        this.activeUserSharedPrefs = loadSharedPrefsForActiveUser();
        setCurrentUser(user);
        logCurrentUserFlags();
    }

    LDUser getCurrentUser() {
        return currentUser;
    }

    SharedPreferences getCurrentUserSharedPrefs() {
        return currentUserSharedPrefs;
    }

    /**
     * Sets the current user. If there are more than MAX_USERS stored in shared preferences,
     * the oldest one is deleted. Updates the active user with the current user's settings, thus
     * triggering any affected listeners.
     *
     * @param user
     */
    void setCurrentUser(LDUser user) {
        Log.d(TAG, "Setting current user to: " + user.getAsUrlSafeBase64());
        currentUser = user;
        currentUserSharedPrefs = loadSharedPrefsForUser(user.getAsUrlSafeBase64());
        syncCurrentUserToActiveUser();

        usersSharedPrefs.edit()
                .putLong(user.getAsUrlSafeBase64(), System.currentTimeMillis())
                .apply();

        while (usersSharedPrefs.getAll().size() > MAX_USERS) {
            List<String> allUsers = getAllUsers();
            String removed = allUsers.get(0);
            Log.d(TAG, "Exceeded max # of users: " + MAX_USERS + " Removing user: " + removed);
            SharedPreferences sharedPrefsToDelete = loadSharedPrefsForUser(removed);
            sharedPrefsToDelete.edit()
                    .clear()
                    .apply();
            usersSharedPrefs.edit()
                    .remove(removed)
                    .apply();
        }
    }

    /**
     * Saves the flags param to {@link SharedPreferences} for the current user.
     * Completely overwrites all values in the current user's {@link SharedPreferences} and
     * saves those values to the active user, triggering any registered {@link FeatureFlagChangeListener}
     * objects.
     *
     * @param flags
     */
    void saveFlagSettings(JsonObject flags) {
        SharedPreferences.Editor currentEditor = currentUserSharedPrefs.edit();
        currentEditor.clear();

        for (Map.Entry<String, JsonElement> entry : flags.entrySet()) {
            JsonElement v = entry.getValue();
            String key = entry.getKey();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                currentEditor.putBoolean(key, v.getAsBoolean());
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                currentEditor.putFloat(key, v.getAsFloat());
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                currentEditor.putString(key, v.getAsString());
            } else if (!v.isJsonNull()) {
                Log.w(TAG, "Found some unknown feature flag type for key: " + key + " and value: " + v.getAsString());
            }
        }
        currentEditor.apply();
        syncCurrentUserToActiveUser();
        logCurrentUserFlags();
    }

    void registerListener(final String key, final FeatureFlagChangeListener listener) {
        OnSharedPreferenceChangeListener sharedPrefsListener = new OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
                if (s.equals(key)) {
                    Log.d(TAG, "Found changed flag: " + key);
                    listener.onFeatureFlagChange(s);
                }
            }
        };
        listeners.put(key, new Pair<>(listener, sharedPrefsListener));
        activeUserSharedPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener);
        Log.d(TAG, "Added listener. Total count: " + listeners.size());
    }

    void unregisterListener(String key, FeatureFlagChangeListener listener) {
        for (Pair<FeatureFlagChangeListener, OnSharedPreferenceChangeListener> pair : listeners.get(key)) {
            if (pair.first.equals(listener)) {
                Log.d(TAG, "Removing listener for key: " + key);
                activeUserSharedPrefs.unregisterOnSharedPreferenceChangeListener(pair.second);
                listeners.remove(key, pair);
            }
        }
    }

    /**
     * Copies the current user's feature flag values to the active user {@link SharedPreferences}.
     * Only changed values will be modified to avoid unwanted triggering of listeners as described
     * <a href="https://developer.android.com/reference/android/content/SharedPreferences.OnSharedPreferenceChangeListener.html">
     *     here</a>.
     * <p/>
     * Any flag values no longer found in the current user will be removed from the
     * active user as well as their listeners.
     */
    private void syncCurrentUserToActiveUser() {
        SharedPreferences.Editor activeEditor = activeUserSharedPrefs.edit();
        Map<String, ?> active = activeUserSharedPrefs.getAll();
        Map<String, ?> current = currentUserSharedPrefs.getAll();

        for (Map.Entry<String, ?> entry : current.entrySet()) {
            Object v = entry.getValue();
            String key = entry.getKey();
            Log.d(TAG, "Maybe saving key: " + key + " with value: " + v);
            if (v instanceof Boolean) {
                if (!v.equals(active.get(key))) {
                    activeEditor.putBoolean(key, (Boolean) v);
                    Log.d(TAG, "Found new boolean flag value for key: " + key + " with value: " + v);
                }
            } else if (v instanceof Float) {
                if (!v.equals(active.get(key))) {
                    activeEditor.putFloat(key, (Float) v);
                    Log.d(TAG, "Found new numeric flag value for key: " + key + " with value: " + v);
                }
            } else if (v instanceof String) {
                if (!v.equals(active.get(key))) {
                    activeEditor.putString(key, (String) v);
                    Log.d(TAG, "Found new json or string flag value for key: " + key + " with value: " + v);
                }
            } else {
                Log.w(TAG, "Found some unknown feature flag type for key: " + key + " and value: " + v);
            }
        }

        // Because we didn't clear the active editor to avoid triggering listeners,
        // we need to remove any flags that have been deleted:
        for (String key : active.keySet()) {
            if (current.get(key) == null) {
                Log.d(TAG, "Deleting value + listeners for key: " + key);
                activeEditor.remove(key);
                listeners.removeAll(key);
            }
        }
        activeEditor.apply();
    }

    private SharedPreferences loadSharedPrefsForUser(String user) {
        String sharedPrefsKey = sharedPrefsBaseKey + "-" + user;
        Log.d(TAG, "Using SharedPreferences key: " + sharedPrefsKey);
        return application.getSharedPreferences(sharedPrefsKey, Context.MODE_PRIVATE);
    }

    private SharedPreferences loadSharedPrefsForActiveUser() {
        String sharedPrefsKey = sharedPrefsBaseKey + "-active";
        Log.d(TAG, "Using SharedPreferences key for active user: " + sharedPrefsKey);
        return application.getSharedPreferences(sharedPrefsKey, Context.MODE_PRIVATE);
    }

    private void logCurrentUserFlags() {
        Map<String, ?> all = currentUserSharedPrefs.getAll();
        if (all.size() == 0) {
            Log.d(TAG, "found zero saved feature flags");
        } else {
            Log.d(TAG, "Found " + all.size() + " feature flags:");
            for (Map.Entry<String, ?> kv : all.entrySet()) {
                Log.d(TAG, "\tKey: " + kv.getKey() + " value: " + kv.getValue());
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
                Log.d(TAG, "Found user: " + k + " with timestamp: " + allTyped.get(k));
            } catch (ClassCastException cce) {
                Log.e(TAG, "Unexpected type! This is not good", cce);
            }
        }

        List<Map.Entry<String, Long>> sorted = new LinkedList<>(allTyped.entrySet());
        Collections.sort(sorted, new EntryComparator());
        List<String> results = new LinkedList<>();
        for (Map.Entry<String, Long> e : sorted) {
            Log.d(TAG, "Found sorted user: " + e.getKey() + " with timestamp: " + e.getValue());
            results.add(e.getKey());
        }
        return results;
    }

    class EntryComparator implements Comparator<Map.Entry<String, Long>> {
        @Override
        public int compare(Map.Entry<String, Long> lhs, Map.Entry<String, Long> rhs) {
            return (int) (lhs.getValue() - rhs.getValue());
        }
    }
}
