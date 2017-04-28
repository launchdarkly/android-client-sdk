package com.launchdarkly.android;


import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import com.google.common.base.Function;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Persists and retrieves feature flag values for different {@link LDUser}s.
 * Also enables realtime updates via registering a {@link FeatureFlagChangeListener}
 * with a feature flag.
 */
class UserManager {
    private static final String TAG = "LDUserManager";
    private static final int MAX_USERS = 5;
    private static UserManager instance;
    private final FeatureFlagFetcher fetcher;
    private final String sharedPrefsBaseKey;
    private volatile boolean initialized = false;

    // The active user is the one that we track for changes to enable listeners.
    // Its values will mirror the current user, but it is a different SharedPreferences
    // than the current user so we can attach OnSharedPreferenceChangeListeners to it.
    private final SharedPreferences activeUserSharedPrefs;

    // Keeps track of the 5 most recent current users
    private final SharedPreferences usersSharedPrefs;

    private final Application application;
    // Maintains references enabling (de)registration of listeners for realtime updates
    private final Multimap<String, Pair<FeatureFlagChangeListener, OnSharedPreferenceChangeListener>> listeners = ArrayListMultimap.create();

    // The current user- we'll always fetch this user from the response we get from the api
    private SharedPreferences currentUserSharedPrefs;
    private LDUser currentUser;

    static UserManager init(Application application, FeatureFlagFetcher fetcher) {
        if (instance != null) {
            return instance;
        }
        instance = new UserManager(application, fetcher);
        return instance;
    }

    static UserManager get() {
        return instance;
    }

    UserManager(Application application, FeatureFlagFetcher fetcher) {
        this.application = application;
        this.fetcher = fetcher;
        this.sharedPrefsBaseKey = "LaunchDarkly-" + application.getPackageName();
        this.usersSharedPrefs = application.getSharedPreferences(sharedPrefsBaseKey + "-users", Context.MODE_PRIVATE);
        this.activeUserSharedPrefs = loadSharedPrefsForActiveUser();
    }

    LDUser getCurrentUser() {
        return currentUser;
    }

    SharedPreferences getCurrentUserSharedPrefs() {
        return currentUserSharedPrefs;
    }

    /**
     * Sets the current user. If there are more than MAX_USERS stored in shared preferences,
     * the oldest one is deleted.
     *
     * @param user
     */
    ListenableFuture<Void> setCurrentUser(final LDUser user) {
        String userBase64 = user.getAsUrlSafeBase64();
        Log.d(TAG, "Setting current user to: [" + userBase64 + "] [" + userBase64ToJson(userBase64) + "]");
        currentUser = user;
        currentUserSharedPrefs = loadSharedPrefsForUser(userBase64);
        ListenableFuture<Void> updateFuture = updateCurrentUser();
        usersSharedPrefs.edit()
                .putLong(userBase64, System.currentTimeMillis())
                .apply();

        while (usersSharedPrefs.getAll().size() > MAX_USERS) {
            List<String> allUsers = getAllUsers();
            String removed = allUsers.get(0);
            Log.d(TAG, "Exceeded max # of users: [" + MAX_USERS + "] Removing user: [" + removed
                    + "] [" + userBase64ToJson(removed) + "]");
            deleteSharedPreferences(removed);
            usersSharedPrefs.edit()
                    .remove(removed)
                    .apply();
        }

        return updateFuture;
    }

    /**
     * Completely deletes a user's saved flag settings and the remaining empty SharedPreferences xml file.
     * @param userKey
     */
    private void deleteSharedPreferences(String userKey) {
        SharedPreferences sharedPrefsToDelete = loadSharedPrefsForUser(userKey);
        sharedPrefsToDelete.edit()
                .clear()
                .apply();

        File file = new File(application.getFilesDir().getParent() + "/shared_prefs/" + sharedPrefsKeyForUser(userKey) + ".xml");
        Log.i(TAG, "Deleting SharedPrefs file:" + file.getAbsolutePath());
        file.delete();
    }

    synchronized ListenableFuture<Void> updateCurrentUser() {
        ListenableFuture<JsonObject> fetchFuture = fetcher.fetch(currentUser);

        Futures.addCallback(fetchFuture, new FutureCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                initialized = true;
                saveFlagSettings(result);
            }

            @Override
            public void onFailure(Throwable t) {
                Log.e(TAG, "Error when attempting to set user: [" + currentUser.getAsUrlSafeBase64()
                        + "] [" + userBase64ToJson(currentUser.getAsUrlSafeBase64()) + "]", t);
                syncCurrentUserToActiveUser();
            }
        });

        // Transform the Future<JsonObject> to Future<Void> since the caller doesn't care about the result.
        return Futures.transform(fetchFuture, new Function<JsonObject, Void>() {
            @Override
            public Void apply(JsonObject input) {
                return null;
            }
        });
    }

    void registerListener(final String key, final FeatureFlagChangeListener listener) {
        OnSharedPreferenceChangeListener sharedPrefsListener = new OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
                if (s.equals(key)) {
                    Log.d(TAG, "Found changed flag: [" + key + "]");
                    listener.onFeatureFlagChange(s);
                }
            }
        };
        listeners.put(key, new Pair<>(listener, sharedPrefsListener));
        activeUserSharedPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener);
        Log.d(TAG, "Added listener. Total count: [" + listeners.size() + "]");
    }

    void unregisterListener(String key, FeatureFlagChangeListener listener) {
        for (Pair<FeatureFlagChangeListener, OnSharedPreferenceChangeListener> pair : listeners.get(key)) {
            if (pair.first.equals(listener)) {
                Log.d(TAG, "Removing listener for key: [" + key + "]");
                activeUserSharedPrefs.unregisterOnSharedPreferenceChangeListener(pair.second);
                listeners.remove(key, pair);
            }
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
    private void saveFlagSettings(JsonObject flags) {
        Log.d(TAG, "saveFlagSettings for user key: "+ currentUser.getKey());
        SharedPreferences.Editor currentEditor = currentUserSharedPrefs.edit();
        currentEditor.clear();

        for (Map.Entry<String, JsonElement> entry : flags.entrySet()) {
            JsonElement v = entry.getValue();
            String key = entry.getKey();
            if (v.isJsonObject() || v.isJsonArray()) {
                currentEditor.putString(key, v.toString());
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                currentEditor.putBoolean(key, v.getAsBoolean());
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                currentEditor.putFloat(key, v.getAsFloat());
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                currentEditor.putString(key, v.getAsString());
            } else if (!v.isJsonNull()) {
                Log.w(TAG, "Found some unknown feature flag type for key: [" + key + "] value: [" + v.getAsString() + "]");
            }
        }
        currentEditor.apply();
        syncCurrentUserToActiveUser();
        logCurrentUserFlags();
    }

    /**
     * Copies the current user's feature flag values to the active user {@link SharedPreferences}.
     * Only changed values will be modified to avoid unwanted triggering of listeners as described
     * <a href="https://developer.android.com/reference/android/content/SharedPreferences.OnSharedPreferenceChangeListener.html">
     * here</a>.
     * <p/>
     * Any flag values no longer found in the current user will be removed from the
     * active user as well as their listeners.
     */
    private void syncCurrentUserToActiveUser() {
        SharedPreferences.Editor activeEditor = activeUserSharedPrefs.edit();
        Map<String, ?> active = activeUserSharedPrefs.getAll();
        Map<String, ?> current = currentUserSharedPrefs.getAll();

        String tag = TAG + " [syncCurrentUserToActiveUser]";

        for (Map.Entry<String, ?> entry : current.entrySet()) {
            Object v = entry.getValue();
            String key = entry.getKey();
            Log.d(tag, "key: [" + key + "] CurrentUser value: [" + v + "] ActiveUser value: [" + active.get(key) + "]");
            if (v instanceof Boolean) {
                if (!v.equals(active.get(key))) {
                    activeEditor.putBoolean(key, (Boolean) v);
                    Log.d(tag, "Found new boolean flag value for key: [" + key + "] with value: [" + v + "]");
                }
            } else if (v instanceof Float) {
                if (!v.equals(active.get(key))) {
                    activeEditor.putFloat(key, (Float) v);
                    Log.d(tag, "Found new numeric flag value for key: [" + key + "] with value: [" + v + "]");
                }
            } else if (v instanceof String) {
                if (!v.equals(active.get(key))) {
                    activeEditor.putString(key, (String) v);
                    Log.d(tag, "Found new json or string flag value for key: [" + key + "] with value: [" + v + "]");
                }
            } else {
                Log.w(tag, "Found some unknown feature flag type for key: [" + key + "] and value: [" + v + "]");
            }
        }

        // Because we didn't clear the active editor to avoid triggering listeners,
        // we need to remove any flags that have been deleted:
        for (String key : active.keySet()) {
            if (current.get(key) == null) {
                Log.d(tag, "Deleting value and listeners for key: [" + key + "]");
                activeEditor.remove(key);
                listeners.removeAll(key);
            }
        }
        activeEditor.apply();
    }

    private SharedPreferences loadSharedPrefsForUser(String user) {
        Log.d(TAG, "Using SharedPreferences key: [" + sharedPrefsKeyForUser(user) + "]");
        return application.getSharedPreferences(sharedPrefsKeyForUser(user), Context.MODE_PRIVATE);
    }

    private String sharedPrefsKeyForUser(String user) {
        return sharedPrefsBaseKey + "-" + user;
    }

    private SharedPreferences loadSharedPrefsForActiveUser() {
        String sharedPrefsKey = sharedPrefsBaseKey + "-active";
        Log.d(TAG, "Using SharedPreferences key for active user: [" + sharedPrefsKey + "]");
        return application.getSharedPreferences(sharedPrefsKey, Context.MODE_PRIVATE);
    }

    private void logCurrentUserFlags() {
        Map<String, ?> all = currentUserSharedPrefs.getAll();
        if (all.size() == 0) {
            Log.d(TAG, "found zero saved feature flags");
        } else {
            Log.d(TAG, "Found " + all.size() + " feature flags:");
            for (Map.Entry<String, ?> kv : all.entrySet()) {
                Log.d(TAG, "\tKey: [" + kv.getKey() + "] value: [" + kv.getValue() + "]");
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
                Log.d(TAG, "Found user: " + userAndTimeStampToHumanReadableString(k, allTyped.get(k)));
            } catch (ClassCastException cce) {
                Log.e(TAG, "Unexpected type! This is not good", cce);
            }
        }

        List<Map.Entry<String, Long>> sorted = new LinkedList<>(allTyped.entrySet());
        Collections.sort(sorted, new EntryComparator());
        List<String> results = new LinkedList<>();
        for (Map.Entry<String, Long> e : sorted) {
            Log.d(TAG, "Found sorted user: " + userAndTimeStampToHumanReadableString(e.getKey(), e.getValue()));
            results.add(e.getKey());
        }
        return results;
    }

    private static String userAndTimeStampToHumanReadableString(String base64, Long timestamp) {
        return base64 + " [" + userBase64ToJson(base64) + "] timestamp: [" + timestamp + "] [" + new Date(timestamp) + "]";
    }

    private static String userBase64ToJson(String base64) {
        return new String(Base64.decode(base64, Base64.URL_SAFE));
    }

    boolean isInitialized() {
        return initialized;
    }

    class EntryComparator implements Comparator<Map.Entry<String, Long>> {
        @Override
        public int compare(Map.Entry<String, Long> lhs, Map.Entry<String, Long> rhs) {
            return (int) (lhs.getValue() - rhs.getValue());
        }
    }
}
