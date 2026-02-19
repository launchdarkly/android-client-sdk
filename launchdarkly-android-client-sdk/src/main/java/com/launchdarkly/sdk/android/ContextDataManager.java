package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.ChangeSet;
import com.launchdarkly.sdk.android.subsystems.ChangeSetType;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.TransactionalDataStore;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;

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
final class ContextDataManager implements TransactionalDataStore {
    private final PersistentDataStoreWrapper.PerEnvironmentData environmentStore;
    private final int maxCachedContexts;
    private final TaskExecutor taskExecutor;
    private final ConcurrentHashMap<String, Set<FeatureFlagChangeListener>> listeners =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<LDAllFlagsListener> allFlagsListeners =
            new CopyOnWriteArrayList<>();
    private final LDLogger logger;

    /**
     * This lock is to protect context, flag, and persistence operations.
     */
    private final Object lock = new Object();

    @NonNull private volatile LDContext currentContext;
    @NonNull private volatile EnvironmentData flags = new EnvironmentData();
    @NonNull private volatile ContextIndex index;

    /** Selector from the last applied changeset that carried one; in-memory only, not persisted. */
    @NonNull private volatile Selector currentSelector = Selector.EMPTY;

    ContextDataManager(
            @NonNull ClientContext clientContext,
            @NonNull PersistentDataStoreWrapper.PerEnvironmentData environmentStore,
            int maxCachedContexts
    ) {
        this.environmentStore = environmentStore;
        this.index = environmentStore.getIndex();
        this.maxCachedContexts = maxCachedContexts;
        this.taskExecutor = ClientContextImpl.get(clientContext).getTaskExecutor();
        this.logger = clientContext.getBaseLogger();
        switchToContext(clientContext.getEvaluationContext());
    }

    /**
     * Switches to providing flag data for the provided context.
     * <p>
     * If the context provided is different than the current state, switches to internally
     * stored flag data and notifies flag listeners.
     *
     * @param context the to switch to
     */
    public void switchToContext(@NonNull LDContext context) {
        synchronized (lock) {
            if (context.equals(currentContext)) {
                return;
            }
            currentContext = context;
        }

        EnvironmentData storedData = getStoredData(context);
        if (storedData == null) {
            logger.debug("No stored flag data is available for this context");
            // here we return to not alter current in memory flag state as
            // current flag state is better than empty flag state in most
            // customer use cases.
            return;
        }

        logger.debug("Using stored flag data for this context");
        // when we switch context, we don't have a selector because we don't currently support persisting the selector.
        applyFullData(context, Selector.EMPTY, storedData.getAll(), false);
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
        // init data is called in the FDv1 path, which does not have a selector
        applyFullData(context, Selector.EMPTY, newData.getAll(), true);
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
                return EnvironmentData.usingExistingFlagsMap(filtered);
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
     * @return true if the update was done; false if it was not done
     */
    public boolean upsert(@NonNull LDContext context, @NonNull Flag flag) {
        EnvironmentData updatedFlags;
        synchronized (lock) {
            if (!context.equals(currentContext)) {
                // if incoming data is not for the current context, reject it.
                return false;
            }

            Flag oldFlag = flags.getFlag(flag.getKey());
            if (oldFlag != null && oldFlag.getVersion() >= flag.getVersion()) {
                return false;
            }
            updatedFlags = flags.withFlagUpdatedOrAdded(flag);
            flags = updatedFlags;

            String hashedContextId = LDUtil.urlSafeBase64HashedContextId(context);
            String fingerprint = LDUtil.urlSafeBase64Hash(context);
            environmentStore.setContextData(hashedContextId, fingerprint, updatedFlags);
            index = index.updateTimestamp(hashedContextId, System.currentTimeMillis());
            environmentStore.setIndex(index);
        }

        Collection<String> updatedFlag = Collections.singletonList(flag.getKey());

        // We really should only be calling to listeners if the value has changed, but we left this
        // unconditional out of fear that we'd uncover bugs in customer code as a result of
        // conditionally notifying listeners
        notifyAllFlagsListeners(updatedFlag);
        notifyFlagListeners(updatedFlag);

        return true;
    }

    @Override
    public void apply(@NonNull LDContext context, @NonNull ChangeSet changeSet) {
        switch (changeSet.getType()) {
            case Full:
                applyFullData(context, changeSet.getSelector(), changeSet.getItems(), changeSet.shouldPersist());
                break;
            case Partial:
                applyPartialData(context, changeSet.getSelector(), changeSet.getItems(), changeSet.shouldPersist());
                break;
            case None:
            default:
                break;
        }
    }

    private void applyFullData(
            @NonNull LDContext context,
            @NonNull Selector selector,
            Map<String, Flag> items,
            boolean shouldPersist
    ) {
        EnvironmentData newData = EnvironmentData.usingExistingFlagsMap(items);
        EnvironmentData oldData;

        synchronized (lock) {
            if (!context.equals(currentContext)) {
                return;
            }
            if (!selector.isEmpty()) {
                currentSelector = selector;
            }
            oldData = flags;
            flags = newData;

            if (shouldPersist) {
                String contextId = LDUtil.urlSafeBase64HashedContextId(context);
                String fingerprint = LDUtil.urlSafeBase64Hash(context);
                List<String> removedContextIds = new ArrayList<>();
                ContextIndex newIndex = index.updateTimestamp(contextId, System.currentTimeMillis())
                        .prune(maxCachedContexts, removedContextIds);
                index = newIndex;

                for (String removedContextId : removedContextIds) {
                    environmentStore.removeContextData(removedContextId);
                    logger.debug("Removed flag data for context {} from persistent store", removedContextId);
                }

                environmentStore.setContextData(contextId, fingerprint, newData);
                environmentStore.setIndex(newIndex);

                if (logger.isEnabled(LDLogLevel.DEBUG)) {
                    logger.debug("Stored context index is now: {}", newIndex.toJson());
                }
                logger.debug("Updated flag data for context {} in persistent store", contextId);
            }
        }

        // Determine which flags were updated and notify listeners, if any.
        // If the flag is new or the value has changed, notify. This logic can be run if
        // the context changes, which can result in an evaluation change even if the version
        // of the flag stays the same. You will notice this logic slightly differs from
        // upsert. Upsert should only be calling to listeners if the value has changed,
        // but we left upsert alone out of fear that we'd uncover bugs in customer code
        // if we added conditionals in upsert.
        Set<String> updatedFlagKeys = new HashSet<>();
        for (Flag newFlag : newData.values()) {
            Flag oldFlag = oldData.getFlag(newFlag.getKey());
            if (oldFlag == null || !oldFlag.getValue().equals(newFlag.getValue())) {
                updatedFlagKeys.add(newFlag.getKey());
            }
        }
        for (Flag oldFlag : oldData.values()) {
            if (newData.getFlag(oldFlag.getKey()) == null) {
                updatedFlagKeys.add(oldFlag.getKey());
            }
        }
        notifyAllFlagsListeners(updatedFlagKeys);
        notifyFlagListeners(updatedFlagKeys);
    }

    private void applyPartialData(
            @NonNull LDContext context,
            @NonNull Selector selector,
            Map<String, Flag> items,
            boolean shouldPersist
    ) {
        EnvironmentData updatedFlags;
        Set<String> updatedFlagKeys = new HashSet<>();

        synchronized (lock) {
            if (!context.equals(currentContext)) {
                return;
            }
            if (!selector.isEmpty()) {
                currentSelector = selector;
            }
            Map<String, Flag> merged = new HashMap<>(flags.getAll());
            for (Map.Entry<String, Flag> entry : items.entrySet()) {
                String key = entry.getKey();
                Flag incoming = entry.getValue();
                Flag existing = merged.get(key);
                if (existing == null || existing.getVersion() < incoming.getVersion()) {
                    merged.put(key, incoming);
                    updatedFlagKeys.add(key);
                }
            }
            updatedFlags = EnvironmentData.usingExistingFlagsMap(merged);
            flags = updatedFlags;

            if (shouldPersist) {
                String hashedContextId = LDUtil.urlSafeBase64HashedContextId(context);
                String fingerprint = LDUtil.urlSafeBase64Hash(context);
                environmentStore.setContextData(hashedContextId, fingerprint, updatedFlags);
                index = index.updateTimestamp(hashedContextId, System.currentTimeMillis());
                environmentStore.setIndex(index);
            }
        }

        notifyAllFlagsListeners(updatedFlagKeys);
        notifyFlagListeners(updatedFlagKeys);
    }

    @Override
    @NonNull
    public Selector getSelector() {
        return currentSelector;
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

    /**
     * Attempts to retrieve data for the specified context, if any, from the persistent store. This
     * does not affect the current context/flags state.
     *
     * @param context the context
     * @return that context's {@link EnvironmentData} from the persistent store, or null if none
     */
    @VisibleForTesting
    public @Nullable EnvironmentData getStoredData(LDContext context) {
        return environmentStore.getContextData(LDUtil.urlSafeBase64HashedContextId(context));
    }

    private void notifyFlagListeners(Collection<String> updatedFlagKeys) {
        if (updatedFlagKeys == null || updatedFlagKeys.isEmpty()) {
            return;
        }
        final Map<String, Set<FeatureFlagChangeListener>> listenersToCall = new HashMap<>();
        for (String flagKey: updatedFlagKeys) {

            // TODO: SDK-1040. This conditional is a short term mitigation for a rare issue where a
            // null flag key is encountered.
            if (flagKey == null) {
                continue;
            }

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
