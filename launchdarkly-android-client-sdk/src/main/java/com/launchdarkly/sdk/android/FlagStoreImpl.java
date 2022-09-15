package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

import android.annotation.SuppressLint;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class FlagStoreImpl implements FlagStore {
    private static final String BASE_NAMESPACE = "LaunchDarkly-";

    @NonNull private final PersistentDataStore store;
    private final String storeNamespace;
    private WeakReference<StoreUpdatedListener> listenerWeakReference;
    private final LDLogger logger;

    FlagStoreImpl(@NonNull PersistentDataStore store, @NonNull String identifier, LDLogger logger) {
        this.store = store;
        this.storeNamespace = BASE_NAMESPACE + identifier + "-flags";
        this.listenerWeakReference = new WeakReference<>(null);
        this.logger = logger;
    }

    @SuppressLint("ApplySharedPref")
    @Override
    public void delete() {
        store.clear(storeNamespace, true);
    }

    @Override
    public void clear() {
        store.clear(storeNamespace, false);
    }

    @Override
    public boolean containsKey(String key) {
        return store.getValue(storeNamespace, key) != null;
    }

    @Nullable
    @Override
    public Flag getFlag(String flagKey) {
        String json = store.getValue(storeNamespace, flagKey);
        try {
            return json == null ? null : gsonInstance().fromJson(json, Flag.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void applyFlagUpdate(FlagUpdate flagUpdate) {
        Pair<Flag, FlagStoreUpdateType> updated = computeUpdate(flagUpdate,
                getFlag(flagUpdate.flagToUpdate()));
        if (updated != null) {
            Flag newFlag = updated.first;
            store.setValue(storeNamespace, newFlag.getKey(), gsonInstance().toJson(newFlag));
            informListenerOfUpdateList(Collections.singletonList(
                    new Pair<>(newFlag.getKey(), updated.second)));
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
        List<Pair<String, FlagStoreUpdateType>> updates = new ArrayList<>();
        Map<String, String> updateValues = new HashMap<>();

        for (FlagUpdate flagUpdate: flagUpdates) {
            String flagKey = flagUpdate.flagToUpdate();
            Pair<Flag, FlagStoreUpdateType> updated = computeUpdate(flagUpdate,
                    getFlag(flagKey));
            if (updated != null) {
                updates.add(new Pair<>(flagKey, updated.second));
                updateValues.put(flagKey, gsonInstance().toJson(updated.first));
            }
        }

        if (!updateValues.isEmpty()) {
            store.setValues(storeNamespace, updateValues);
        }
        if (!updates.isEmpty()) {
            informListenerOfUpdateList(updates);
        }
    }

    @Override
    public void clearAndApplyFlagUpdates(List<? extends FlagUpdate> newFlags) {
        Map<String, Flag> oldFlags = getAllMap();
        Set<String> clearedKeys = new HashSet<>(oldFlags.keySet());

        List<Pair<String, FlagStoreUpdateType>> updates = new ArrayList<>();
        Map<String, String> updateValues = new HashMap<>();

        for (FlagUpdate flagUpdate: newFlags) {
            String flagKey = flagUpdate.flagToUpdate();
            Flag oldFlag = oldFlags.get(flagKey);
            Pair<Flag, FlagStoreUpdateType> updated = computeUpdate(flagUpdate, oldFlag);
            if (updated != null) {
                updates.add(new Pair<>(flagKey, updated.second));
                updateValues.put(flagKey, gsonInstance().toJson(updated.first));
            }
            clearedKeys.remove(flagKey);
        }
        for (String clearedKey: clearedKeys) {
            updateValues.put(clearedKey, null);
            updates.add(new Pair<>(clearedKey, FlagStoreUpdateType.FLAG_DELETED));
        }

        if (!updateValues.isEmpty()) {
            store.setValues(storeNamespace, updateValues);
        }

        informListenerOfUpdateList(updates);
        // Currently we are calling the listener even if there are zero updates. This may not be
        // necessary but is preserved for backward compatibility until the logic can be sorted out.
    }

    @Override
    public Collection<Flag> getAllFlags() {
        return getAllMap().values();
    }

    private Map<String, Flag> getAllMap() {
        Map<String, Flag> ret = new HashMap<>();
        for (String key: store.getKeys(storeNamespace)) {
            Flag flag = getFlag(key);
            if (flag != null) {
                ret.put(key, flag);
            }
        }
        return ret;
    }

    @Override
    public void registerOnStoreUpdatedListener(StoreUpdatedListener storeUpdatedListener) {
        listenerWeakReference = new WeakReference<>(storeUpdatedListener);
    }

    @Override
    public void unregisterOnStoreUpdatedListener() {
        listenerWeakReference.clear();
    }

    private Pair<Flag, FlagStoreUpdateType> computeUpdate(FlagUpdate flagUpdate, Flag oldFlag) {
        Flag newFlag = flagUpdate.updateFlag(oldFlag);
        if (newFlag == oldFlag) {
            return null;
        }
        FlagStoreUpdateType updateType;
        if (newFlag.isDeleted()) {
            updateType = FlagStoreUpdateType.FLAG_DELETED;
        } else if (oldFlag == null || oldFlag.isDeleted()) {
            updateType = FlagStoreUpdateType.FLAG_CREATED;
        } else {
            updateType = FlagStoreUpdateType.FLAG_UPDATED;
        }
        return new Pair<>(newFlag, updateType);
    }
}
