package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import java.io.File;
import java.util.Collection;
import java.util.Map;

final class SharedPreferencesPersistentDataStore implements PersistentDataStore {
    private final Application application;

    public SharedPreferencesPersistentDataStore(Application application) {
        this.application = application;
    }

    @Override
    public String getValue(String storeNamespace, String key) {
        SharedPreferences prefs = application.getSharedPreferences(storeNamespace, Context.MODE_PRIVATE);
        try {
            return prefs.getString(key, null);
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
        SharedPreferences prefs = application.getSharedPreferences(storeNamespace, Context.MODE_PRIVATE);
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
        SharedPreferences prefs = application.getSharedPreferences(storeNamespace, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, String> kv: keysAndValues.entrySet()) {
            editor.putString(kv.getKey(), kv.getValue());
        }
        editor.apply();
    }

    @Override
    public Collection<String> getKeys(String storeNamespace) {
        SharedPreferences prefs = application.getSharedPreferences(storeNamespace, Context.MODE_PRIVATE);
        return prefs.getAll().keySet();
    }

    @Override
    public void clear(String storeNamespace, boolean fullyDelete) {
        SharedPreferences prefs = application.getSharedPreferences(storeNamespace, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        if (fullyDelete) {
            File file = new File(application.getFilesDir().getParent() + "/shared_prefs/" +
                    storeNamespace + ".xml");
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
