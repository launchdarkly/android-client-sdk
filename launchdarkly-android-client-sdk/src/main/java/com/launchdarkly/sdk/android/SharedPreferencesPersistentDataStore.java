package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

final class SharedPreferencesPersistentDataStore implements PersistentDataStore {
    private final Application application;
    private final LDLogger logger;

    public SharedPreferencesPersistentDataStore(Application application, LDLogger logger) {
        this.application = application;
        this.logger = logger;
    }

    public SharedPreferencesPersistentDataStore(Application application) {
        this(application, LDLogger.none());
    }

    @Override
    public String getValue(String storeNamespace, String key) {
        SharedPreferences prefs = getSharedPreferences(storeNamespace);
        try {
            String ret = prefs.getString(key, null);
            return ret;
        } catch (ClassCastException e) {
            try {
                // In the past, we sometimes stored numeric values directly as numbers via the
                // SharedPreferences API. Our new persistence model is simpler and expects strings.
                Long longValue = prefs.getLong(key, 0);
                return longValue == null ? null : String.valueOf(longValue);
            } catch (ClassCastException e1) {
                return null;
            }
        }
    }

    @Override
    public void setValue(String storeNamespace, String key, String value) {
        SharedPreferences prefs = getSharedPreferences(storeNamespace);
        SharedPreferences.Editor editor = prefs.edit();
        if (value == null) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
        editor.apply();
    }

    @Override
    public void setValues(String storeNamespace, Map<String, String> keysAndValues) {
        SharedPreferences prefs = getSharedPreferences(storeNamespace);
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, String> kv: keysAndValues.entrySet()) {
            editor.putString(kv.getKey(), kv.getValue());
        }
        editor.apply();
    }

    @Override
    public Collection<String> getKeys(String storeNamespace) {
        SharedPreferences prefs = getSharedPreferences(storeNamespace);
        return prefs.getAll().keySet();
    }

    @Override
    public Collection<String> getAllNamespaces() {
        // Implementation note: we are reading directly from the filesystem, which means that we will
        // miss any updates that have been saved asynchronously with SharedPreferences.Editor.apply().
        // Asynchronous updating is desirable for the SDK in general, and getAllNamespaces() should
        // only be used for data migration logic, so this is an acceptable tradeoff.

        File directory = new File(application.getFilesDir().getParent() + "/shared_prefs/");
        File[] files = directory.listFiles();
        List<String> ret = new ArrayList<>();
        if (files == null) {
            return ret;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".xml")) {
                ret.add(file.getName().substring(0, file.getName().length() - 4));
            }
        }
        return ret;
    }

    @Override
    public void clear(String storeNamespace, boolean fullyDelete) {
        SharedPreferences prefs = getSharedPreferences(storeNamespace);
        prefs.edit().clear().apply();
        if (fullyDelete) {
            File file = new File(application.getFilesDir().getParent() + "/shared_prefs/" +
                    storeNamespace + ".xml");
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private SharedPreferences getSharedPreferences(String storeNamespace) {
        // Note, the Android API guarantees that whenever we call getSharedPreferences with the
        // same string, we receive the same object, so it is OK to make this call repeatedly
        // rather than caching the object.
        return application.getSharedPreferences(storeNamespace, Context.MODE_PRIVATE);
    }
}
