package com.launchdarkly.android;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

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

class UserLocalSharedPreferences {

    private static final int MAX_USERS = 5;

    // The active user is the one that we track for changes to enable listeners.
    // Its values will mirror the current user, but it is a different SharedPreferences
    // than the current user so we can attach OnSharedPreferenceChangeListeners to it.
    private final SharedPreferences activeUserSharedPrefs;

    // Keeps track of the 5 most recent current users
    private final SharedPreferences usersSharedPrefs;

    private final Application application;
    // Maintains references enabling (de)registration of listeners for realtime updates
    private final Multimap<String, Pair<FeatureFlagChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>> listeners;

    // The current user- we'll always fetch this user from the response we get from the api
    private SharedPreferences currentUserSharedPrefs;

    private String mobileKey;

    UserLocalSharedPreferences(Application application, String mobileKey) {
        this.application = application;
        this.usersSharedPrefs = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "users", Context.MODE_PRIVATE);
        this.mobileKey = mobileKey;
        this.activeUserSharedPrefs = loadSharedPrefsForActiveUser();
        HashMultimap<String, Pair<FeatureFlagChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>> multimap = HashMultimap.create();
        listeners = Multimaps.synchronizedMultimap(multimap);
    }

    SharedPreferences getCurrentUserSharedPrefs() {
        return currentUserSharedPrefs;
    }

    void setCurrentUser(LDUser user) {
        currentUserSharedPrefs = loadSharedPrefsForUser(user.getSharedPrefsKey());

        usersSharedPrefs.edit()
                .putLong(user.getSharedPrefsKey(), System.currentTimeMillis())
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

    private SharedPreferences loadSharedPrefsForUser(String user) {
        Timber.d("Using SharedPreferences key: [%s]", sharedPrefsKeyForUser(user));
        return application.getSharedPreferences(sharedPrefsKeyForUser(user), Context.MODE_PRIVATE);
    }

    private String sharedPrefsKeyForUser(String user) {
        return LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + user;
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

    /**
     * Completely deletes a user's saved flag settings and the remaining empty SharedPreferences xml file.
     *
     * @param userKey
     */
    @SuppressWarnings("JavaDoc")
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

    private SharedPreferences loadSharedPrefsForActiveUser() {
        String sharedPrefsKey = LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "active";
        Timber.d("Using SharedPreferences key for active user: [%s]", sharedPrefsKey);
        return application.getSharedPreferences(sharedPrefsKey, Context.MODE_PRIVATE);
    }

    Collection<Pair<FeatureFlagChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>> getListener(String key) {
        synchronized (listeners) {
            return listeners.get(key);
        }
    }

    void registerListener(final String key, final FeatureFlagChangeListener listener) {
        SharedPreferences.OnSharedPreferenceChangeListener sharedPrefsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
                if (s.equals(key)) {
                    Timber.d("Found changed flag: [%s]", key);
                    listener.onFeatureFlagChange(s);
                }
            }
        };
        synchronized (listeners) {
            listeners.put(key, new Pair<>(listener, sharedPrefsListener));
            Timber.d("Added listener. Total count: [%s]", listeners.size());
        }
        activeUserSharedPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener);

    }

    void unRegisterListener(String key, FeatureFlagChangeListener listener) {
        synchronized (listeners) {
            Iterator<Pair<FeatureFlagChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>> it = listeners.get(key).iterator();
            while (it.hasNext()) {
                Pair<FeatureFlagChangeListener, SharedPreferences.OnSharedPreferenceChangeListener> pair = it.next();
                if (pair.first.equals(listener)) {
                    Timber.d("Removing listener for key: [%s]", key);
                    activeUserSharedPrefs.unregisterOnSharedPreferenceChangeListener(pair.second);
                    it.remove();
                }
            }
        }
    }

    void saveCurrentUserFlags(SharedPreferencesEntries sharedPreferencesEntries) {
        sharedPreferencesEntries.clearAndSave(currentUserSharedPrefs);
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
    void syncCurrentUserToActiveUser() {
        SharedPreferences.Editor activeEditor = activeUserSharedPrefs.edit();
        Map<String, ?> active = activeUserSharedPrefs.getAll();
        Map<String, ?> current = currentUserSharedPrefs.getAll();

        for (Map.Entry<String, ?> entry : current.entrySet()) {
            Object v = entry.getValue();
            String key = entry.getKey();
            Timber.d("key: [%s] CurrentUser value: [%s] ActiveUser value: [%s]", key, v, active.get(key));
            if (v instanceof Boolean) {
                if (!v.equals(active.get(key))) {
                    activeEditor.putBoolean(key, (Boolean) v);
                    Timber.d("Found new boolean flag value for key: [%s] with value: [%s]", key, v);
                }
            } else if (v instanceof Float) {
                if (!v.equals(active.get(key))) {
                    activeEditor.putFloat(key, (Float) v);
                    Timber.d("Found new numeric flag value for key: [%s] with value: [%s]", key, v);
                }
            } else if (v instanceof String) {
                if (!v.equals(active.get(key))) {
                    activeEditor.putString(key, (String) v);
                    Timber.d("Found new json or string flag value for key: [%s] with value: [%s]", key, v);
                }
            } else {
                Timber.w("Found some unknown feature flag type for key: [%s] with value: [%s]", key, v);
            }
        }

        // Because we didn't clear the active editor to avoid triggering listeners,
        // we need to remove any flags that have been deleted:
        for (String key : active.keySet()) {
            if (current.get(key) == null) {
                Timber.d("Deleting value and listeners for key: [%s]", key);
                activeEditor.remove(key);
                synchronized (listeners) {
                    listeners.removeAll(key);
                }
            }
        }
        activeEditor.apply();

    }

    void logCurrentUserFlags() {
        Map<String, ?> all = currentUserSharedPrefs.getAll();
        if (all.size() == 0) {
            Timber.d("found zero saved feature flags");
        } else {
            Timber.d("Found %s feature flags:", all.size());
            for (Map.Entry<String, ?> kv : all.entrySet()) {
                Timber.d("\tKey: [%s] value: [%s]", kv.getKey(), kv.getValue());
            }
        }
    }

    void deleteCurrentUserFlag(String flagKey) {
        Timber.d("Request to delete key: [%s]", flagKey);

        removeCurrentUserFlag(flagKey);

    }

    @SuppressLint("ApplySharedPref")
    private void removeCurrentUserFlag(String flagKey) {
        SharedPreferences.Editor editor = currentUserSharedPrefs.edit();
        Map<String, ?> current = currentUserSharedPrefs.getAll();

        for (Map.Entry<String, ?> entry : current.entrySet()) {
            Object v = entry.getValue();
            String key = entry.getKey();

            if (key.equals(flagKey)) {
                editor.remove(flagKey);
                Timber.d("Deleting key: [%s] CurrentUser value: [%s]", key, v);
            }
        }

        editor.commit();
    }

    void patchCurrentUserFlags(SharedPreferencesEntries sharedPreferencesEntries) {
        sharedPreferencesEntries.update(currentUserSharedPrefs);
    }

    class EntryComparator implements Comparator<Map.Entry<String, Long>> {
        @Override
        public int compare(Map.Entry<String, Long> lhs, Map.Entry<String, Long> rhs) {
            return (int) (lhs.getValue() - rhs.getValue());
        }
    }

    @SuppressLint("ApplySharedPref")
    static class SharedPreferencesEntries {

        private final List<SharedPreferencesEntry> sharedPreferencesEntryList;

        SharedPreferencesEntries(List<SharedPreferencesEntry> sharedPreferencesEntryList) {
            this.sharedPreferencesEntryList = sharedPreferencesEntryList;
        }

        void clearAndSave(SharedPreferences sharedPreferences) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.clear();
            for (SharedPreferencesEntry entry : sharedPreferencesEntryList) {
                entry.saveWithoutApply(editor);
            }
            editor.commit();
        }

        void update(SharedPreferences sharedPreferences) {

            SharedPreferences.Editor editor = sharedPreferences.edit();

            for (SharedPreferencesEntry entry : sharedPreferencesEntryList) {
                entry.saveWithoutApply(editor);
            }
            editor.commit();
        }
    }

    abstract static class SharedPreferencesEntry<K> {

        protected final String key;
        protected final K value;

        SharedPreferencesEntry(String key, K value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public K getValue() {
            return value;
        }

        abstract void saveWithoutApply(SharedPreferences.Editor editor);
    }

    static class BooleanSharedPreferencesEntry extends SharedPreferencesEntry<Boolean> {

        BooleanSharedPreferencesEntry(String key, Boolean value) {
            super(key, value);
        }

        @Override
        void saveWithoutApply(SharedPreferences.Editor editor) {
            editor.putBoolean(key, value);
        }
    }

    static class StringSharedPreferencesEntry extends SharedPreferencesEntry<String> {

        StringSharedPreferencesEntry(String key, String value) {
            super(key, value);
        }

        @Override
        void saveWithoutApply(SharedPreferences.Editor editor) {
            editor.putString(key, value);
        }
    }

    static class FloatSharedPreferencesEntry extends SharedPreferencesEntry<Float> {

        FloatSharedPreferencesEntry(String key, Float value) {
            super(key, value);
        }

        @Override
        void saveWithoutApply(SharedPreferences.Editor editor) {
            editor.putFloat(key, value);
        }
    }

}
