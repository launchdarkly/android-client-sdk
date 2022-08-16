package com.launchdarkly.sdk.android;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

class SharedPrefsFlagStore implements FlagStore {

    private static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";
    private final String prefsKey;
    private final Application application;
    private SharedPreferences sharedPreferences;
    private WeakReference<StoreUpdatedListener> listenerWeakReference;

    SharedPrefsFlagStore(@NonNull Application application, @NonNull String identifier) {
        this.application = application;
        this.prefsKey = SHARED_PREFS_BASE_KEY + identifier + "-flags";
        this.sharedPreferences = application.getSharedPreferences(prefsKey, Context.MODE_PRIVATE);
        this.listenerWeakReference = new WeakReference<>(null);
    }

    @SuppressLint("ApplySharedPref")
    @Override
    public void delete() {
        sharedPreferences.edit().clear().commit();
        sharedPreferences = null;

        File file = new File(application.getFilesDir().getParent() + "/shared_prefs/" + prefsKey + ".xml");
        LDConfig.log().i("Deleting SharedPrefs file:%s", file.getAbsolutePath());

        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Override
    public void clear() {
        sharedPreferences.edit().clear().apply();
    }

    @Override
    public boolean containsKey(String key) {
        return sharedPreferences.contains(key);
    }

    @Nullable
    @Override
    public Flag getFlag(String flagKey) {
        return LDUtil.sharedPrefsGetGson(sharedPreferences, Flag.class, flagKey);
    }

    private Pair<String, FlagStoreUpdateType> applyFlagUpdateNoCommit(@NonNull SharedPreferences.Editor editor, @NonNull FlagUpdate flagUpdate) {
        String flagKey = flagUpdate.flagToUpdate();
        if (flagKey == null) {
            return null;
        }
        Flag flag = getFlag(flagKey);
        Flag newFlag = flagUpdate.updateFlag(flag);
        if (flag != null && newFlag == null) {
            editor.remove(flagKey);
            return new Pair<>(flagKey, FlagStoreUpdateType.FLAG_DELETED);
        } else if (flag == null && newFlag != null) {
            String flagData = GsonCache.getGson().toJson(newFlag);
            editor.putString(flagKey, flagData);
            return new Pair<>(flagKey, FlagStoreUpdateType.FLAG_CREATED);
        } else if (flag != newFlag) {
            String flagData = GsonCache.getGson().toJson(newFlag);
            editor.putString(flagKey, flagData);
            return new Pair<>(flagKey, FlagStoreUpdateType.FLAG_UPDATED);
        }
        return null;
    }

    @Override
    public void applyFlagUpdate(FlagUpdate flagUpdate) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        Pair<String, FlagStoreUpdateType> update = applyFlagUpdateNoCommit(editor, flagUpdate);
        editor.apply();
        StoreUpdatedListener storeUpdatedListener = listenerWeakReference.get();
        if (update != null && storeUpdatedListener != null) {
            storeUpdatedListener.onStoreUpdate(Collections.singletonList(new Pair<>(update.first, update.second)));
        }
    }

    private void informListenerOfUpdateList(List<Pair<String, FlagStoreUpdateType>> updates) {
        StoreUpdatedListener storeUpdatedListener = listenerWeakReference.get();
        if (storeUpdatedListener != null) {
            storeUpdatedListener.onStoreUpdate(updates);
        }
    }

    @Override
    public void applyFlagUpdates(List<? extends FlagUpdate> flagUpdates) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        ArrayList<Pair<String, FlagStoreUpdateType>> updates = new ArrayList<>();
        for (FlagUpdate flagUpdate : flagUpdates) {
            Pair<String, FlagStoreUpdateType> update = applyFlagUpdateNoCommit(editor, flagUpdate);
            if (update != null) {
                updates.add(update);
            }
        }
        editor.apply();
        informListenerOfUpdateList(updates);
    }

    @Override
    public void clearAndApplyFlagUpdates(List<? extends FlagUpdate> newFlags) {
        Gson gson = GsonCache.getGson();
        Map<String, Flag> cachedFlags = LDUtil.sharedPrefsGetAllGson(sharedPreferences, Flag.class);
        // here we explicitly copy the keySet()
        // this is because modifying a keySet() also modifies the underlying map
        // and we modify this further up to track changes
        Set<String> clearedKeys = new HashSet<>(cachedFlags.keySet());
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        ArrayList<Pair<String, FlagStoreUpdateType>> updates = new ArrayList<>();
        for (FlagUpdate flagUpdate : newFlags) {
            String flagKey = flagUpdate.flagToUpdate();
            if (flagKey == null) {
                continue;
            }
            Flag newFlag = flagUpdate.updateFlag(null);
            if (newFlag != null) {
                String flagData = gson.toJson(newFlag);
                editor.putString(flagKey, flagData);

                // track that this key has not been deleted
                clearedKeys.remove(flagKey);

                Flag cachedFlag = cachedFlags.get(flagKey);

                if (cachedFlag != null) {
                    Integer cv = cachedFlag.getVersion();
                    Integer nv = newFlag.getVersion();

                    if (!Objects.equals(cv, nv)) {
                        // only track updates if the versions of the flag has changed
                        updates.add(new Pair<>(flagKey, FlagStoreUpdateType.FLAG_UPDATED));
                    }
                    continue;
                }

                // Flag not present in cached flags so mark it as newly created
                updates.add(new Pair<>(flagKey, FlagStoreUpdateType.FLAG_CREATED));
            }
        }
        editor.apply();
        for (String clearedKey : clearedKeys) {
            updates.add(new Pair<>(clearedKey, FlagStoreUpdateType.FLAG_DELETED));
        }
        informListenerOfUpdateList(updates);
    }

    @Override
    public Collection<Flag> getAllFlags() {
        return LDUtil.sharedPrefsGetAllGson(sharedPreferences, Flag.class).values();
    }

    @Override
    public void registerOnStoreUpdatedListener(StoreUpdatedListener storeUpdatedListener) {
        listenerWeakReference = new WeakReference<>(storeUpdatedListener);
    }

    @Override
    public void unregisterOnStoreUpdatedListener() {
        listenerWeakReference.clear();
    }
}
