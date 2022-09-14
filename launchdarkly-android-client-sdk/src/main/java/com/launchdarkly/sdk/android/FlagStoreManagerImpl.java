package com.launchdarkly.sdk.android;

import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

class FlagStoreManagerImpl implements FlagStoreManager, StoreUpdatedListener {

    private static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";

    @NonNull
    private final FlagStoreFactory flagStoreFactory;
    @NonNull
    private final PersistentDataStoreWrapper.PerEnvironmentData environmentStore;
    private final int maxCachedContexts;

    private FlagStore currentFlagStore;
    private final ConcurrentHashMap<String, Set<FeatureFlagChangeListener>> listeners;
    private final CopyOnWriteArrayList<LDAllFlagsListener> allFlagsListeners;
    private final LDLogger logger;

    FlagStoreManagerImpl(@NonNull FlagStoreFactory flagStoreFactory,
                         @NonNull PersistentDataStoreWrapper.PerEnvironmentData environmentStore,
                         int maxCachedContexts,
                         @NonNull LDLogger logger) {
        this.flagStoreFactory = flagStoreFactory;
        this.environmentStore = environmentStore;
        this.maxCachedContexts = maxCachedContexts;
        this.listeners = new ConcurrentHashMap<>();
        this.allFlagsListeners = new CopyOnWriteArrayList<>();
        this.logger = logger;
    }

    @Override
    public void switchToContext(String hashedContextId) {
        if (currentFlagStore != null) {
            currentFlagStore.unregisterOnStoreUpdatedListener();
            if (maxCachedContexts == 0) {
                // Normally, removing old flag data sets is done by the ContextIndex/prune logic
                // below. However, in the special case where maxCachedContexts is zero, there won't
                // ever be an old context in the index and so it won't be pruned... so we need to
                // explicitly throw out the old flag data here. This isn't quite right because it
                // means there's a brief time when no flag data exists-- but this logic will be
                // replaced when we unify the ContextManager and FlagStoreManager components.
                currentFlagStore.clear();
            }
        }
        currentFlagStore = flagStoreFactory.createFlagStore(hashedContextId);
        currentFlagStore.registerOnStoreUpdatedListener(this);

        // Store the context's key and the current time in the data store so it can be removed when
        // maxCachedContexts is exceeded.
        ContextIndex index = environmentStore.getIndex();
        index = index.updateTimestamp(hashedContextId, System.currentTimeMillis());
        List<String> purgedContexts = new ArrayList<>();
        index = index.prune(maxCachedContexts, purgedContexts);
        environmentStore.setIndex(index);
        for (String purgedContext: purgedContexts) {
            environmentStore.removeContextData(purgedContext);
        }
    }

    @Override
    public FlagStore getCurrentContextStore() {
        return currentFlagStore;
    }

    @Override
    public void registerListener(String key, FeatureFlagChangeListener listener) {
        Map<FeatureFlagChangeListener, Boolean> backingMap = new ConcurrentHashMap<>();
        Set<FeatureFlagChangeListener> newSet = Collections.newSetFromMap(backingMap);
        newSet.add(listener);
        Set<FeatureFlagChangeListener> oldSet = listeners.putIfAbsent(key, newSet);
        if (oldSet != null) {
            oldSet.add(listener);
            logger.debug("Added listener. Total count: [{}]", oldSet.size());
        } else {
            logger.debug("Added listener. Total count: 1");
        }
    }

    @Override
    public void unRegisterListener(String key, FeatureFlagChangeListener listener) {
        Set<FeatureFlagChangeListener> keySet = listeners.get(key);
        if (keySet != null) {
            boolean removed = keySet.remove(listener);
            if (removed) {
                logger.debug("Removing listener for key: [{}]", key);
            }
        }
    }

    @Override
    public void registerAllFlagsListener(LDAllFlagsListener listener) {
        allFlagsListeners.add(listener);
    }

    @Override
    public void unregisterAllFlagsListener(LDAllFlagsListener listener) {
        allFlagsListeners.remove(listener);
    }

    private void dispatchStoreUpdateCallback(final String flagKey, final FlagStoreUpdateType flagStoreUpdateType) {
        // We make sure to call listener callbacks on the main thread, as we consistently did so in
        // the past by virtue of using SharedPreferences to implement the callbacks.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Get the listeners for the specific key
            Set<FeatureFlagChangeListener> keySet = listeners.get(flagKey);
            // If there are any listeners for this key
            if (keySet != null) {
                // We only call the listener if the flag is a new flag or updated.
                if (flagStoreUpdateType != FlagStoreUpdateType.FLAG_DELETED) {
                    for (FeatureFlagChangeListener listener : keySet) {
                        listener.onFeatureFlagChange(flagKey);
                    }
                } else {
                    // When flag is deleted we remove the corresponding listeners
                    listeners.remove(flagKey);
                }
            }
        } else {
            // Call ourselves on the main thread
            new Handler(Looper.getMainLooper()).post(() -> dispatchStoreUpdateCallback(flagKey, flagStoreUpdateType));
        }
    }

    @Override
    public void onStoreUpdate(List<Pair<String, FlagStoreUpdateType>> updates) {
        List<String> flagKeys = new ArrayList<>();
        for (Pair<String, FlagStoreUpdateType> update : updates) {
            flagKeys.add(update.first);
            dispatchStoreUpdateCallback(update.first, update.second);
        }
        for (LDAllFlagsListener allFlagsListener : allFlagsListeners) {
            allFlagsListener.onChange(flagKeys);
        }
    }

    public Collection<FeatureFlagChangeListener> getListenersByKey(String key) {
        Set<FeatureFlagChangeListener> res = listeners.get(key);
        return res == null ? new HashSet<>() : res;
    }
}
