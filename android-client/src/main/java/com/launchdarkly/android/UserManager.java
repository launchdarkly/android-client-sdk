package com.launchdarkly.android;


import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonElement;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

class UserManager {
    private static final String TAG = "LDUserManager";
    private static final int MAX_USERS = 5;
    private final SharedPreferences currentUserSharedPrefs;
    private final SharedPreferences usersSharedPrefs;
    private final String sharedPrefsBaseKey;
    private final Application application;

    private LDUser currentUser;

    UserManager(Application application, LDUser user) {
        this.application = application;
        this.currentUser = user;
        this.sharedPrefsBaseKey = "LaunchDarkly-" + application.getPackageName();
        this.usersSharedPrefs = application.getSharedPreferences(sharedPrefsBaseKey + "-users", Context.MODE_PRIVATE);
        this.currentUserSharedPrefs = loadSharedPrefsForUser(user.getAsUrlSafeBase64());
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
     * @param user
     */
    void setCurrentUser(LDUser user) {
        Log.d(TAG, "Setting current user to: " + user.getAsUrlSafeBase64());
        currentUser = user;
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
     * Saves the flags param to SharedPreferences for the current user.
     * @param flags
     */
    void saveFlagSettingsForUser(Map<String, JsonElement> flags) {
        SharedPreferences.Editor editor = currentUserSharedPrefs.edit();
        editor.clear();
        for (Map.Entry<String, JsonElement> entry : flags.entrySet()) {
            JsonElement v = entry.getValue();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                editor.putBoolean(entry.getKey(), v.getAsBoolean());
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                editor.putFloat(entry.getKey(), v.getAsFloat());
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                editor.putString(entry.getKey(), v.getAsString());
            }
        }
        editor.apply();
        logAllFlags();
    }


    private SharedPreferences loadSharedPrefsForUser(String user) {
        String sharedPrefsKey = sharedPrefsBaseKey + "-" + user;
        Log.i(TAG, "Using SharedPreferences key: " + sharedPrefsKey);
        return application.getSharedPreferences(sharedPrefsKey, Context.MODE_PRIVATE);
    }

    private void logAllFlags() {
        Map<String, ?> all = currentUserSharedPrefs.getAll();
        if (all.size() == 0) {
            Log.d(TAG, "found zero saved feature flags");
        } else {
            Log.d(TAG, "Found feature flags:");
            for (Map.Entry<String, ?> kv : all.entrySet()) {
                Log.d(TAG, "Key: " + kv.getKey() + " value: " + kv.getValue());
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
