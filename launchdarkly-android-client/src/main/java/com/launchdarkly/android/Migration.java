package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import timber.log.Timber;

class Migration {

    static void migrateWhenNeeded(Application application, LDConfig config) {
        SharedPreferences migrations = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "migrations", Context.MODE_PRIVATE);

        if (!migrations.contains("v2.6.0")) {
            Timber.d("Migrating to v2.6.0 multi-environment shared preferences");

            File directory = new File(application.getFilesDir().getParent() + "/shared_prefs/");
            File[] files = directory.listFiles();
            ArrayList<String> filenames = new ArrayList<>();
            for (File file : files) {
                if (file.isFile())
                    filenames.add(file.getName());
            }

            filenames.remove(LDConfig.SHARED_PREFS_BASE_KEY + "id.xml");
            filenames.remove(LDConfig.SHARED_PREFS_BASE_KEY + "users.xml");
            filenames.remove(LDConfig.SHARED_PREFS_BASE_KEY + "version.xml");
            filenames.remove(LDConfig.SHARED_PREFS_BASE_KEY + "active.xml");
            filenames.remove(LDConfig.SHARED_PREFS_BASE_KEY + "summaryevents.xml");
            filenames.remove(LDConfig.SHARED_PREFS_BASE_KEY + "migrations.xml");

            Iterator<String> nameIter = filenames.iterator();
            while (nameIter.hasNext()) {
                String name = nameIter.next();
                if (!name.startsWith(LDConfig.SHARED_PREFS_BASE_KEY) || !name.endsWith(".xml")) {
                    nameIter.remove();
                    continue;
                }
                for (String mobileKey : config.getMobileKeys().values()) {
                    if (name.contains(mobileKey)) {
                        nameIter.remove();
                        break;
                    }
                }
            }

            ArrayList<String> userKeys = new ArrayList<>();
            for (String filename : filenames) {
                userKeys.add(filename.substring(LDConfig.SHARED_PREFS_BASE_KEY.length(), filename.length() - 4));
            }

            boolean allSuccess = true;
            for (Map.Entry<String, String> mobileKeys : config.getMobileKeys().entrySet()) {
                String mobileKey = mobileKeys.getValue();
                boolean users = copySharedPreferences(application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "users", Context.MODE_PRIVATE),
                        application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "-users", Context.MODE_PRIVATE));
                boolean version = copySharedPreferences(application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "version", Context.MODE_PRIVATE),
                        application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "-version", Context.MODE_PRIVATE));
                boolean active = copySharedPreferences(application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "active", Context.MODE_PRIVATE),
                        application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "-active", Context.MODE_PRIVATE));
                boolean stores = true;
                for (String key : userKeys) {
                    boolean store = copySharedPreferences(application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + key, Context.MODE_PRIVATE),
                            application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + key + "-user", Context.MODE_PRIVATE));
                    stores = stores && store;
                }
                allSuccess = allSuccess && users && version && active && stores;
            }

            if (allSuccess) {
                Timber.d("Migration to v2.6.0 multi-environment shared preferences successful");
                boolean logged = migrations.edit().putString("v2.6.0", "v2.6.0").commit();
                if (logged) {
                    application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "users", Context.MODE_PRIVATE).edit().clear().apply();
                    application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "version", Context.MODE_PRIVATE).edit().clear().apply();
                    application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "active", Context.MODE_PRIVATE).edit().clear().apply();
                    application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "summaryevents", Context.MODE_PRIVATE).edit().clear().apply();
                    for (String key : userKeys) {
                        application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + key, Context.MODE_PRIVATE).edit().clear().apply();
                    }
                }
            }
        }
    }

    private static boolean copySharedPreferences(SharedPreferences oldPreferences, SharedPreferences newPreferences) {
        SharedPreferences.Editor editor = newPreferences.edit();

        for (Map.Entry<String, ?> entry : oldPreferences.getAll().entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();

            if (value instanceof Boolean)
                editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Float)
                editor.putFloat(key, (Float) value);
            else if (value instanceof Integer)
                editor.putInt(key, (Integer) value);
            else if (value instanceof Long)
                editor.putLong(key, (Long) value);
            else if (value instanceof String)
                editor.putString(key, ((String) value));
        }

        return editor.commit();
    }

}
