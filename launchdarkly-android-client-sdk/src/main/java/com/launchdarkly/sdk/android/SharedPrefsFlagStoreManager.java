package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

class SharedPrefsFlagStoreManager implements FlagStoreManager, StoreUpdatedListener {

    private static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";

    @NonNull
    private final FlagStoreFactory flagStoreFactory;
    @NonNull
    private final String mobileKey;
    private final int maxCachedContexts;

    private FlagStore currentFlagStore;
    private final SharedPreferences contextsSharedPrefs;
    private final ConcurrentHashMap<String, Set<FeatureFlagChangeListener>> listeners;
    private final CopyOnWriteArrayList<LDAllFlagsListener> allFlagsListeners;
    private final LDLogger logger;

    SharedPrefsFlagStoreManager(@NonNull Application application,
                                @NonNull String mobileKey,
                                @NonNull FlagStoreFactory flagStoreFactory,
                                int maxCachedContexts,
                                @NonNull LDLogger logger) {
        this.mobileKey = mobileKey;
        this.flagStoreFactory = flagStoreFactory;
        this.maxCachedContexts = maxCachedContexts;
        this.contextsSharedPrefs = application.getSharedPreferences(SHARED_PREFS_BASE_KEY + mobileKey + "-users", Context.MODE_PRIVATE);
        this.listeners = new ConcurrentHashMap<>();
        this.allFlagsListeners = new CopyOnWriteArrayList<>();
        this.logger = logger;
    }

    @Override
    public void switchToContext(String hashedContextKey) {
        String storeId = storeIdentifierForContext(hashedContextKey);
        if (currentFlagStore != null) {
            currentFlagStore.unregisterOnStoreUpdatedListener();
        }
        currentFlagStore = flagStoreFactory.createFlagStore(storeId);
        currentFlagStore.registerOnStoreUpdatedListener(this);

        // Store the context's key and the current time in contextsSharedPrefs so it can be removed when
        // maxCachedContexts is exceeded.
        contextsSharedPrefs.edit()
                .putLong(hashedContextKey, System.currentTimeMillis())
                .apply();

        int contextsStored = contextsSharedPrefs.getAll().size();
        // Negative numbers represent an unlimited number of cached contexts. The active context is not
        // considered a cached context, so we subtract one.
        int contextsToRemove = maxCachedContexts >= 0 ? contextsStored - maxCachedContexts - 1 : 0;
        if (contextsToRemove > 0) {
            Iterator<String> oldestFirstContexts = getCachedContexts(storeId).iterator();
            // Remove oldest contexts until we are at the configured limit.
            for (int i = 0; i < contextsToRemove; i++) {
                String removed = oldestFirstContexts.next();
                logger.debug("Exceeded max # of contexts: [{}] Removing context: [{}]", maxCachedContexts, removed);
                // Load FlagStore for oldest context and delete it.
                flagStoreFactory.createFlagStore(storeIdentifierForContext(removed)).delete();
                // Remove entry from contextsSharedPrefs.
                contextsSharedPrefs.edit().remove(removed).apply();
            }
        }
    }

    private String storeIdentifierForContext(String hashedContextKey) {
        return mobileKey + hashedContextKey;
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

    // Gets cached contexts (does not include the active context) sorted by creation time (oldest first)
    private Collection<String> getCachedContexts(String activeContext) {
        Map<String, ?> all = contextsSharedPrefs.getAll();
        all.remove(activeContext);
        TreeMap<Long, String> sortedMap = new TreeMap<>();
        //get typed versions of the contexts' timestamps and insert into sorted TreeMap
        for (String k : all.keySet()) {
            try {
                sortedMap.put((Long) all.get(k), k);
                logger.debug("Found context: {}", contextAndTimeStampToHumanReadableString(k, (Long) all.get(k)));
            } catch (ClassCastException cce) {
                LDUtil.logExceptionAtErrorLevel(logger, cce, "Unexpected type! This is not good");
            }
        }
        return sortedMap.values();
    }

    private static String contextAndTimeStampToHumanReadableString(String contextSharedPrefsKey, Long timestamp) {
        return contextSharedPrefsKey + " [" + contextSharedPrefsKey + "] timestamp: [" + timestamp + "]" + " [" + new Date(timestamp) + "]";
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
