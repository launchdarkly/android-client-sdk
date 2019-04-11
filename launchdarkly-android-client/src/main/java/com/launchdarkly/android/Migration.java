package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.launchdarkly.android.gson.GsonCache;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import timber.log.Timber;

class Migration {

    static void migrateWhenNeeded(Application application, LDConfig config) {
        SharedPreferences migrations = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "migrations", Context.MODE_PRIVATE);

        if (migrations.contains("v2.7.0")) {
            return;
        }

        if (!migrations.contains("v2.6.0")) {
            migrate_2_7_fresh(application, config);
        }

        if (migrations.contains("v2.6.0") && !migrations.contains("v2.7.0")) {
            migrate_2_7_from_2_6(application);
        }
    }

    private static String reconstructFlag(String key, String metadata, Object value) {
        JsonObject flagJson = GsonCache.getGson().fromJson(metadata, JsonObject.class);
        flagJson.addProperty("key", key);
        if (value instanceof Float) {
            flagJson.addProperty("value", (Float) value);
        } else if (value instanceof Boolean) {
            flagJson.addProperty("value", (Boolean) value);
        } else if (value instanceof String) {
            try {
                JsonElement jsonVal = GsonCache.getGson().fromJson((String) value, JsonElement.class);
                flagJson.add("value", jsonVal);
            } catch (JsonSyntaxException unused) {
                flagJson.addProperty("value", (String) value);
            }
        }

        return GsonCache.getGson().toJson(flagJson);
    }

    private static void migrate_2_7_fresh(Application application, LDConfig config) {
        Timber.d("Migrating to v2.7.0 shared preferences store");

        ArrayList<String> userKeys = getUserKeysPre_2_6(application, config);
        SharedPreferences versionSharedPrefs = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "version", Context.MODE_PRIVATE);
        Map<String, ?> flagData = versionSharedPrefs.getAll();
        Set<String> flagKeys = flagData.keySet();

        boolean allSuccess = true;
        for (Map.Entry<String, String> mobileKeys : config.getMobileKeys().entrySet()) {
            String mobileKey = mobileKeys.getValue();
            boolean users = copySharedPreferences(application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "users", Context.MODE_PRIVATE),
                    application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "-users", Context.MODE_PRIVATE));
            boolean stores = true;
            for (String key : userKeys) {
                Map<String, ?> flagValues = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + key, Context.MODE_PRIVATE).getAll();
                String prefsKey = LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + key + "-flags";
                SharedPreferences.Editor userFlagStoreEditor = application.getSharedPreferences(prefsKey, Context.MODE_PRIVATE).edit();
                for (String flagKey : flagKeys) {
                    String flagString = reconstructFlag(flagKey, (String) flagData.get(flagKey), flagValues.get(flagKey));
                    userFlagStoreEditor.putString(flagKey, flagString);
                }
                stores = stores && userFlagStoreEditor.commit();
            }
            allSuccess = allSuccess && users && stores;
        }

        if (allSuccess) {
            Timber.d("Migration to v2.7.0 shared preferences store successful");
            SharedPreferences migrations = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "migrations", Context.MODE_PRIVATE);
            boolean logged = migrations.edit().putString("v2.7.0", "v2.7.0").commit();
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

    private static void migrate_2_7_from_2_6(Application application) {
        Timber.d("Migrating to v2.7.0 shared preferences store from v2.6.0");

        Multimap<String, String> keyUsers = getUserKeys_2_6(application);

        boolean allSuccess = true;
        for (String mobileKey : keyUsers.keySet()) {
            SharedPreferences versionSharedPrefs = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "-version", Context.MODE_PRIVATE);
            Map<String, ?> flagData = versionSharedPrefs.getAll();
            Set<String> flagKeys = flagData.keySet();

            for (String key : keyUsers.get(mobileKey)) {
                Map<String, ?> flagValues = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + key + "-user", Context.MODE_PRIVATE).getAll();
                SharedPreferences.Editor userFlagStoreEditor = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + key + "-flags", Context.MODE_PRIVATE).edit();
                for (String flagKey : flagKeys) {
                    String flagString = reconstructFlag(flagKey, (String) flagData.get(flagKey), flagValues.get(flagKey));
                    userFlagStoreEditor.putString(flagKey, flagString);
                }
                allSuccess = allSuccess && userFlagStoreEditor.commit();
            }
        }

        if (allSuccess) {
            Timber.d("Migration to v2.7.0 shared preferences store successful");
            SharedPreferences migrations = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "migrations", Context.MODE_PRIVATE);
            boolean logged = migrations.edit().putString("v2.7.0", "v2.7.0").commit();
            if (logged) {
                for (String mobileKey : keyUsers.keySet()) {
                    application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "-version", Context.MODE_PRIVATE).edit().clear().apply();
                    application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "-active", Context.MODE_PRIVATE).edit().clear().apply();
                    for (String key : keyUsers.get(mobileKey)) {
                        application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + key + "-user", Context.MODE_PRIVATE).edit().clear().apply();
                    }
                }
            }
        }
    }

    static ArrayList<String> getUserKeysPre_2_6(Application application, LDConfig config) {
        File directory = new File(application.getFilesDir().getParent() + "/shared_prefs/");
        File[] files = directory.listFiles();
        if (files == null) {
            return new ArrayList<>();
        }
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
                if (mobileKey != null && name.contains(mobileKey)) {
                    nameIter.remove();
                    break;
                }
            }
        }

        ArrayList<String> userKeys = new ArrayList<>();
        for (String filename : filenames) {
            userKeys.add(filename.substring(LDConfig.SHARED_PREFS_BASE_KEY.length(), filename.length() - 4));
        }
        return userKeys;
    }

    static Multimap<String, String> getUserKeys_2_6(Application application) {
        File directory = new File(application.getFilesDir().getParent() + "/shared_prefs/");
        File[] files = directory.listFiles();
        ArrayList<String> filenames = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith(LDConfig.SHARED_PREFS_BASE_KEY) && name.endsWith("-user.xml")) {
                filenames.add(file.getName());
            }
        }

        Multimap<String, String> keyUserMap = HashMultimap.create();
        for (String filename : filenames) {
            String strip = filename.substring(LDConfig.SHARED_PREFS_BASE_KEY.length(), filename.length() - 9);
            int splitAt = strip.length() - 44;
            if (splitAt > 0) {
                String mobileKey = strip.substring(0, splitAt);
                String userKey = strip.substring(splitAt);
                keyUserMap.put(mobileKey, userKey);
            }
        }
        return keyUserMap;
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
