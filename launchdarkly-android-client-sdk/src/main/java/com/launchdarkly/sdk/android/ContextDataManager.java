package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The component that maintains the state of the current context and last known flag values, syncs
 * it with persistent storage if persistent storage is enabled, and notifies any relevant listeners
 * when flags change.
 * <p>
 * Flag operations are done against an in-memory cache. The cache is loaded and deserialized from
 * persistent storage only when the context changes; any updates cause it to be reserialized and
 * rewritten to storage.
 * <p>
 * This component also manages update versioning and deleted item placeholders. It filters out any
 * deleted item placeholders when querying flags, so the rest of the SDK does not need to know about
 * them; they are only relevant in the update versioning logic.
 * <p>
 * No Android APIs are called directly from this component; data storage is done via whatever
 * implementation of PersistentDataStore was used to create the PersistentDataStoreWrapper, and
 * deferred listener calls are done via the {@link TaskExecutor} abstraction.
 */
final class ContextDataManager {
    static final ContextHasher HASHER = new ContextHasher();

    private final PersistentDataStoreWrapper.PerEnvironmentData environmentStore;
    private final int maxCachedContexts;
    private final TaskExecutor taskExecutor;
    private final ConcurrentHashMap<String, Set<FeatureFlagChangeListener>> listeners =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<LDAllFlagsListener> allFlagsListeners =
            new CopyOnWriteArrayList<>();
    private final LDLogger logger;
    private final Object writerLock = new Object();

    @NonNull private volatile LDContext currentContext;
    @NonNull private volatile EnvironmentData flags = new EnvironmentData();
    @NonNull private volatile ContextIndex index = null;
    private volatile String flagsContextId = null;

    ContextDataManager(
            @NonNull PersistentDataStoreWrapper.PerEnvironmentData environmentStore,
            @NonNull LDContext initialContext,
            int maxCachedContexts,
            @NonNull TaskExecutor taskExecutor,
            @NonNull LDLogger logger
    ) {
        this.currentContext = initialContext;
        this.environmentStore = environmentStore;
        this.maxCachedContexts = maxCachedContexts;
        this.taskExecutor = taskExecutor;
        this.logger = logger;
    }

    /**
     * Returns the current context.
     * <p>
     * This piece of state is shared between LDClient and other components. The current context is
     * set at initialization time, and also whenever {@link LDClient#identify(LDContext)} is
     * called. The fact that the current context has changed does NOT necessarily mean we have flag
     * data for that context yet; that is updated separately by {@link #initData(LDContext, EnvironmentData)}.
     *
     * @return the current context
     */
    public @NonNull LDContext getCurrentContext() {
        return currentContext;
    }

    /**
     * Sets the current context.
     * <p>
     * This piece of state is shared between LDClient and other components. Changing the current
     * context affects only the return value of {@link #getCurrentContext()}. It does NOT mean we
     * have flag data for that context yet; that is updated separately by
     * {@link #initData(LDContext, EnvironmentData)}.

     * @param newContext the new context
     */
    public void setCurrentContext(@NonNull LDContext newContext) {
        currentContext = newContext;
    }

    /**
     * Attempts to retrieve data for the specified context, if any, from the persistent store. This
     * does not affect the current context/flags state.
     *
     * @param context the context
     * @return that context's {@link EnvironmentData} from the persistent store, or null if none
     */
    public @Nullable EnvironmentData getStoredData(LDContext context) {
        return environmentStore.getContextData(hashedContextId(context));
    }

    /**
     * Replaces the current flag data and updates the "current context" state. The context is added
     * to the list of stored contexts-- evicting old context data if necessary.
     *
     * @param context the new context
     * @param newData the new flag data
     */
    public void initData(
            @NonNull LDContext context,
            @NonNull EnvironmentData newData
    ) {
        logger.debug("Initializing with new flag data for this context");
        initDataInternal(context, newData, true);
    }

    /**
     * Attempts to initialize the flag data state from the persistent store. If there was a set of
     * stored flag data for this context, it updates the current flag state and returns true; also,
     * the context is added to the list of stored contexts-- evicting old context data if necessary.
     * If there was no stored data, it leaves the current flag state as is and returns false.
     *
     * @param context the new context
     * @return true if successful
     */
    public boolean initFromStoredData(@NonNull LDContext context) {
        EnvironmentData storedData = getStoredData(context);
        if (storedData == null) {
            logger.debug("No stored flag data is available for this context");
            return false;
        }
        logger.debug("Using stored flag data for this context");
        initDataInternal(context, storedData, false);
        return true;
    }

    private void initDataInternal(
            @NonNull LDContext context,
            @NonNull EnvironmentData newData,
            boolean writeFlagsToPersistentStore
    ) {
        List<String> removedContextIds = new ArrayList<>();
        String contextId = hashedContextId(context);
        EnvironmentData oldData;
        ContextIndex newIndex;

        synchronized (writerLock) {
            currentContext = context;
            oldData = flags;
            flags = newData;
            if (index == null) {
                index = environmentStore.getIndex();
            }
            newIndex = index.updateTimestamp(contextId, System.currentTimeMillis())
                    .prune(maxCachedContexts, removedContextIds);
            index = newIndex;
            flagsContextId = contextId;
        }

        for (String removedContextId: removedContextIds) {
            environmentStore.removeContextData(removedContextId);
            logger.debug("Removed flag data for context {} from persistent store", removedContextId);
        }
        if (writeFlagsToPersistentStore && maxCachedContexts != 0) {
            environmentStore.setContextData(contextId, newData);
            logger.debug("Updated flag data for context {} in persistent store", contextId);
        }
        if (logger.isEnabled(LDLogLevel.DEBUG)) {
            logger.debug("Stored context index is now: {}", newIndex.toJson());
        }
        environmentStore.setIndex(newIndex);

        // Determine which flags were updated and notify listeners, if any
        Set<String> updatedFlagKeys = new HashSet<>();
        for (Flag newFlag: newData.values()) {
            Flag oldFlag = oldData.getFlag(newFlag.getKey());
            if (oldFlag == null || oldFlag.getVersion() != newFlag.getVersion()) {
                updatedFlagKeys.add(newFlag.getKey());
            }
        }
        for (Flag oldFlag: oldData.values()) {
            if (newData.getFlag(oldFlag.getKey()) == null) {
                updatedFlagKeys.add(oldFlag.getKey());
            }
        }
        notifyAllFlagsListeners(updatedFlagKeys);
        notifyFlagListeners(updatedFlagKeys);
    }

    /**
     * Parses JSON flag data and, if successful, updates the current flag state from it and writes
     * it to persistent storage; then calls the callback with a success or error result.
     *
     * @param context the new context
     * @param newDataJson the new flag data as JSON
     * @param onCompleteListener the listener to call on success or failure
     */
    public void initDataFromJson(
        @NonNull LDContext context,
        @NonNull String newDataJson,
        LDUtil.ResultCallback<Void> onCompleteListener
    ) {
        EnvironmentData data;
        try {
            data = EnvironmentData.fromJson(newDataJson);
        } catch (Exception e) {
            logger.debug("Received invalid JSON flag data: {}", newDataJson);
            onCompleteListener.onError(new LDFailure("Invalid JSON received from flags endpoint",
                    e, LDFailure.FailureType.INVALID_RESPONSE_BODY));
            return;
        }
        initData(currentContext, data);
        onCompleteListener.onSuccess(null);
    }

    /**
     * Attempts to get a flag by key from the current flags. This always uses the in-memory cache,
     * not persistent storage.
     *
     * @param key the flag key
     * @return the flag; null if not found, or if deleted (will never return a deleted placeholder)
     */
    public @Nullable Flag getNonDeletedFlag(@NonNull String key) {
        Flag ret = flags.getFlag(key);
        return ret == null || ret.isDeleted() ? null : ret;
    }

    /**
     * Returns all current non-deleted flags. This always uses the in-memory cache, not persistent
     * storage.
     *
     * @return
     */
    public @NonNull EnvironmentData getAllNonDeleted() {
        EnvironmentData data = flags;
        for (Flag f: data.values()) {
            if (f.isDeleted()) {
                // Since there's at least one deleted flag, we need to create a copy of the whole
                // data set to filter out the deleted flags.
                Map<String, Flag> filtered = new HashMap<>();
                for (Flag f1: data.values()) {
                    if (!f1.isDeleted()) {
                        filtered.put(f1.getKey(), f1);
                    }
                }
                return new EnvironmentData(filtered);
            }
        }
        return data;
    }

    /**
     * Attempts to update or insert a flag.
     * <p>
     * This implements the usual versioning logic for updates: the update only succeeds if the
     * version is greater than the version of any current data for the same key. If successful,
     * it also updates persistent storage. Therefore {@link com.launchdarkly.sdk.android.subsystems.PersistentDataStore}
     * implementations do not need to implement their own version checking.
     *
     * @param flag the updated flag data or deleted item placeholder
     * @return true if the update was done; false if it was not done due to a too-low version
     */
    public boolean upsert(@NonNull Flag flag) {
        EnvironmentData updatedFlags;
        String contextId;
        synchronized (writerLock) {
            Flag oldFlag = flags.getFlag(flag.getKey());
            if (oldFlag != null && oldFlag.getVersion() >= flag.getVersion()) {
                return false;
            }
            updatedFlags = flags.withFlagUpdatedOrAdded(flag);
            flags = updatedFlags;
            contextId = flagsContextId;
        }
        environmentStore.setContextData(contextId, updatedFlags);

        notifyFlagListeners(Collections.singletonList(flag.getKey()));

        return true;
    }

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

    public void unregisterListener(String key, FeatureFlagChangeListener listener) {
        Set<FeatureFlagChangeListener> keySet = listeners.get(key);
        if (keySet != null) {
            boolean removed = keySet.remove(listener);
            if (removed) {
                logger.debug("Removing listener for key: [{}]", key);
            }
        }
    }

    public void registerAllFlagsListener(LDAllFlagsListener listener) {
        allFlagsListeners.add(listener);
    }

    public void unregisterAllFlagsListener(LDAllFlagsListener listener) {
        allFlagsListeners.remove(listener);
    }

    public Collection<FeatureFlagChangeListener> getListenersByKey(String key) {
        Set<FeatureFlagChangeListener> res = listeners.get(key);
        return res == null ? new HashSet<>() : res;
    }

    public static String hashedContextId(final LDContext context) {
        return HASHER.hash(context.getFullyQualifiedKey());
    }

    private void notifyFlagListeners(Collection<String> updatedFlagKeys) {
        if (updatedFlagKeys == null || updatedFlagKeys.isEmpty()) {
            return;
        }
        final Map<String, Set<FeatureFlagChangeListener>> listenersToCall = new HashMap<>();
        for (String flagKey: updatedFlagKeys) {
            Set<FeatureFlagChangeListener> flagListeners = listeners.get(flagKey);
            if (flagListeners != null && !flagListeners.isEmpty()) {
                listenersToCall.put(flagKey, flagListeners);
            }
        }
        if (listenersToCall.isEmpty()) {
            return;
        }
        // We make sure to call listener callbacks on the main thread, as we consistently did so in
        // the past by virtue of using SharedPreferences to implement the callbacks.
        taskExecutor.executeOnMainThread(() -> {
            for (Map.Entry<String, Set<FeatureFlagChangeListener>> flagListeners: listenersToCall.entrySet()) {
                for (FeatureFlagChangeListener listener: flagListeners.getValue()) {
                    listener.onFeatureFlagChange(flagListeners.getKey());
                }
            }
        });
    }

    private void notifyAllFlagsListeners(Collection<String> updatedFlagKeys) {
        if (updatedFlagKeys == null || updatedFlagKeys.isEmpty() || allFlagsListeners.isEmpty()) {
            return;
        }
        List<String> keysAsList = new ArrayList<>(updatedFlagKeys);
        taskExecutor.executeOnMainThread(() -> {
            for (LDAllFlagsListener listener: allFlagsListeners) {
                listener.onChange(keysAsList);
            }
        });
    }
}
