package com.launchdarkly.sdk.android;

import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogger;

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
    @NonNull private final PersistentDataStoreWrapper.PerEnvironmentData environmentStore;
    private final String hashedContextId;
    private WeakReference<StoreUpdatedListener> listenerWeakReference;
    private final LDLogger logger;

    FlagStoreImpl(
            @NonNull PersistentDataStoreWrapper.PerEnvironmentData environmentStore,
            @NonNull String hashedContextId,
            LDLogger logger
    ) {
        this.environmentStore = environmentStore;
        this.hashedContextId = hashedContextId;
        this.listenerWeakReference = new WeakReference<>(null);
        this.logger = logger;
    }

    @Override
    public void clear() {
        environmentStore.removeContextData(hashedContextId);
    }

    @Override
    public boolean containsKey(String key) {
        EnvironmentData data = environmentStore.getContextData(hashedContextId);
        return data != null && data.getFlag(key) != null;
    }

    @Nullable
    @Override
    public Flag getFlag(String flagKey) {
        EnvironmentData data = environmentStore.getContextData(hashedContextId);
        return data == null ? null : data.getFlag(flagKey);
    }

    @Override
    public void applyFlagUpdate(FlagUpdate flagUpdate) {
        Pair<Flag, FlagStoreUpdateType> updated = computeUpdate(flagUpdate,
                getFlag(flagUpdate.flagToUpdate()));
        if (updated != null) {
            Flag newFlag = updated.first;
            EnvironmentData oldData = environmentStore.getContextData(hashedContextId);
            EnvironmentData newData = (oldData == null ? new EnvironmentData() : oldData)
                    .withFlagUpdatedOrAdded(newFlag);
            environmentStore.setContextData(hashedContextId, newData);
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
    public void applyFlagUpdates(List<? extends FlagUpdate> allUpdates) {
        applyFlagUpdates(allUpdates, false);
    }

    @Override
    public void clearAndApplyFlagUpdates(List<? extends FlagUpdate> allUpdates) {
        applyFlagUpdates(allUpdates, true);
    }

    private void applyFlagUpdates(List<? extends FlagUpdate> allUpdates, boolean replaceAll) {
        Map<String, Flag> oldFlags = getAllMap();
        Set<String> keysNotUpdated = new HashSet<>(oldFlags.keySet());

        List<Pair<String, FlagStoreUpdateType>> updates = new ArrayList<>();
        Map<String, String> updateValues = new HashMap<>();
        Map<String, Flag> newFlags = new HashMap<>(oldFlags);

        for (FlagUpdate flagUpdate: allUpdates) {
            String flagKey = flagUpdate.flagToUpdate();
            Flag oldFlag = oldFlags.get(flagKey);
            Pair<Flag, FlagStoreUpdateType> updated = computeUpdate(flagUpdate, oldFlag);
            if (updated != null) {
                updates.add(new Pair<>(flagKey, updated.second));
                newFlags.put(flagKey, updated.first);
            }
            keysNotUpdated.remove(flagKey);
        }
        if (replaceAll) {
            for (String unusedKey : keysNotUpdated) {
                newFlags.remove(unusedKey);
                updates.add(new Pair<>(unusedKey, FlagStoreUpdateType.FLAG_DELETED));
            }
        }

        environmentStore.setContextData(hashedContextId, new EnvironmentData(newFlags));

        informListenerOfUpdateList(updates);
        // Currently we are calling the listener even if there are zero updates. This may not be
        // necessary but is preserved for backward compatibility until the logic can be sorted out.
    }

    @Override
    public Collection<Flag> getAllFlags() {
        return getAllMap().values();
    }

    private Map<String, Flag> getAllMap() {
        EnvironmentData data = environmentStore.getContextData(hashedContextId);
        return data == null ? new HashMap<>() : data.getAll();
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
