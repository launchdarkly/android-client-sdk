package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import java.io.File;
import java.util.Collection;
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
            logger.warn("### getValue({}, {}) = {}", storeNamespace, key, ret);
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
            logger.warn("### removing ({}, {})", storeNamespace, key);
            editor.remove(key);
        } else {
            logger.warn("### setting ({}, {}) to {}", storeNamespace, key, value);
            editor.putString(key, value);
        }
        editor.apply();
        logger.warn("### applied");
    }

    @Override
    public void setValues(String storeNamespace, Map<String, String> keysAndValues) {
        SharedPreferences prefs = getSharedPreferences(storeNamespace);
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, String> kv: keysAndValues.entrySet()) {
            logger.warn("### setting ({}, {}) to {}", storeNamespace, kv.getKey(), kv.getValue());
            editor.putString(kv.getKey(), kv.getValue());
        }
        editor.apply();
        logger.warn("### applied");
    }

    @Override
    public Collection<String> getKeys(String storeNamespace) {
        SharedPreferences prefs = getSharedPreferences(storeNamespace);
        return prefs.getAll().keySet();
    }

    @Override
    public void clear(String storeNamespace, boolean fullyDelete) {
        SharedPreferences prefs = getSharedPreferences(storeNamespace);
        prefs.edit().clear().apply();
        logger.warn("### cleared {} & applied", storeNamespace);
        if (fullyDelete) {
            File file = new File(application.getFilesDir().getParent() + "/shared_prefs/" +
                    storeNamespace + ".xml");
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            logger.warn("### deleted {}", file.getPath());
        }
    }

    private SharedPreferences getSharedPreferences(String storeNamespace) {
        // Note, the Android API guarantees that whenever we call getSharedPreferences with the
        // same string, we receive the same object, so it is OK to make this call repeatedly
        // rather than caching the object.
        return application.getSharedPreferences(storeNamespace, Context.MODE_PRIVATE);
    }
}
