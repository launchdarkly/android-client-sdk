package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.TransactionalDataStore;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.fdv2.Selector;

import com.launchdarkly.sdk.android.subsystems.Callback;

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
    private final PersistentDataStoreWrapper.PerEnvironmentData environmentStore;
    private final int maxCachedContexts;
    private final TaskExecutor taskExecutor;
    private final ConcurrentHashMap<String, Set<FeatureFlagChangeListener>> listeners =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<LDAllFlagsListener> allFlagsListeners =
            new CopyOnWriteArrayList<>();

    @Nullable private volatile ContextSwitchListener contextSwitchListener;
    private final LDLogger logger;

    /**
     * This lock is to protect context, flag, and persistence operations.
     */
    private final Object lock = new Object();

    @NonNull private volatile LDContext currentContext;
    @NonNull private volatile EnvironmentData flags = new EnvironmentData();
    @NonNull private volatile ContextIndex index;
    @NonNull private volatile ContextDataManagerView currentView;

    /** Selector from the last applied changeset that carried one; in-memory only, not persisted. */
    @NonNull private Selector currentSelector = Selector.EMPTY;

    /**
     * @param skipCacheLoad true when an FDv2 cache initializer will handle loading cached
     *                      flags as the first step in the initializer chain, making the
     *                      cache load in {@link #switchToContext} redundant
     */
    ContextDataManager(
            @NonNull ClientContext clientContext,
            @NonNull PersistentDataStoreWrapper.PerEnvironmentData environmentStore,
            int maxCachedContexts,
            boolean skipCacheLoad
    ) {
        this.environmentStore = environmentStore;
        this.index = environmentStore.getIndex();
        this.maxCachedContexts = maxCachedContexts;
        this.taskExecutor = ClientContextImpl.get(clientContext).getTaskExecutor();
        this.logger = clientContext.getBaseLogger();
        this.currentView = new ContextDataManagerView();
        switchToContext(clientContext.getEvaluationContext(), skipCacheLoad, LDUtil.noOpCallback());
    }

    /**
     * Switches to providing flag data for the provided context.
     * <p>
     * If the context provided is different than the current context, the previous
     * {@link ContextDataManagerView} is invalidated, a new view is created, stored flag
     * data is loaded (if available and {@code skipCacheLoad} is false), and the registered
     * {@link ContextSwitchListener} is notified with the new context, view, and completion
     * callback.
     * <p>
     * If the context is the same as the current context, the callback is completed
     * immediately with success.
     *
     * @param context       the context to switch to
     * @param skipCacheLoad true to skip loading cached data from the persistent store here
     *                      (FDv2: cache initializer loads it); the listener and completion
     *                      callback are still invoked
     * @param onCompletion  callback for when downstream work is complete
     */
    public void switchToContext(@NonNull LDContext context, boolean skipCacheLoad, @NonNull Callback<Void> onCompletion) {
        ContextDataManagerView newView;
        synchronized (lock) {
            if (context.equals(currentContext)) {
                onCompletion.onSuccess(null);
                return;
            }
            // this call to invalidate disables data operations being performed through the view
            currentView.invalidate();
            currentContext = context;
            currentSelector = Selector.EMPTY;
            newView = new ContextDataManagerView();
            currentView = newView;
        }

        if (!skipCacheLoad) {
            EnvironmentData storedData = getStoredData(context);
            if (storedData == null) {
                logger.debug("No stored flag data is available for this context");
            } else {
                logger.debug("Using stored flag data for this context");
                applyFullData(context, Selector.EMPTY, storedData.getAll(), false);
            }
        }

        // At the time of writing this, we only needed one listener (the ConnectivityManager) and the
        // code was simpler to support a single listener.  If you need to support multiple listeners,
        // you must consider how to handle the onComplete callback being send to many listeners.
        ContextSwitchListener listener = contextSwitchListener;
        if (listener != null) {
            listener.onContextChanged(context, newView, onCompletion);
        } else {
            onCompletion.onSuccess(null);
        }
    }

    /**
     * Sets the listener that will be notified on every context switch. The listener
     * is immediately called with the current context and view (with a no-op callback)
     * so it can initialize its state.
     *
     * @param listener the listener to set
     */
    public void setContextSwitchListener(@NonNull ContextSwitchListener listener) {
        this.contextSwitchListener = listener;
        listener.onContextChanged(currentContext, currentView, LDUtil.noOpCallback());
    }

    public void removeContextSwitchListener() {
        this.contextSwitchListener = null;
    }

    /**
     * Replaces the current flag data and updates the "current context" state. The context is added
     * to the list of stored contexts-- evicting old context data if necessary.
     *
     * @param context the new context
     * @param newData the new flag data
     */
    @VisibleForTesting
    void initData(
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
    @VisibleForTesting
    boolean upsert(@NonNull LDContext context, @NonNull Flag flag) {
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

    @VisibleForTesting
    void apply(@NonNull LDContext context, @NonNull ChangeSet<Map<String, Flag>> changeSet) {
        switch (changeSet.getType()) {
            case Full:
                applyFullData(context, changeSet.getSelector(), changeSet.getData(), changeSet.shouldPersist());
                break;
            case Partial:
                applyPartialData(context, changeSet.getSelector(), changeSet.getData(), changeSet.shouldPersist());
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
            currentSelector = selector;
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
            currentSelector = selector;
            Map<String, Flag> merged = new HashMap<>(flags.getAll());
            for (Map.Entry<String, Flag> entry : items.entrySet()) {
                merged.put(entry.getKey(), entry.getValue());
                updatedFlagKeys.add(entry.getKey());
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

    @VisibleForTesting
    @NonNull
    Selector getSelector() {
        synchronized (lock) {
            return currentSelector;
        }
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

    /**
     * Listener interface for context switch events.
     * <p>
     * Implementations receive notifications when the managed context changes. Each
     * notification includes the new context, a valid {@link ContextDataManagerView}
     * scoped to that context, and a completion callback.
     * <p>
     * {@link #onContextChanged} is also called immediately when a listener is registered
     * via {@link ContextDataManager#setContextSwitchListener}, with the current
     * context and view (and a no-op callback). This allows late-registering listeners
     * to receive the current state without a separate interaction.
     */
    interface ContextSwitchListener {
        /**
         * Called when the managed context changes, or immediately when listener is
         * registered, with the current context and view.
         *
         * @param context      the new (or current) evaluation context
         * @param view         a valid {@link ContextDataManagerView} scoped to this context;
         *                     any previously issued views have already been invalidated
         * @param onCompletion callback to invoke when the downstream work triggered by
         *                     this context switch is complete; a no-op callback at
         *                     registration time
         */
        void onContextChanged(
                @NonNull LDContext context,
                @NonNull ContextDataManagerView view,
                @NonNull Callback<Void> onCompletion
        );
    }

    /**
     * A scoped, invalidatable view of {@link ContextDataManager} that gates all data
     * operations through the enclosing ContextDataManager's locking protections.
     * <p>
     * Each time the managed context changes, the previous view is permanently invalidated
     * and a new view is created. Once invalidated, all write operations become no-ops and
     * {@link #getSelector()} returns {@link Selector#EMPTY}. This prevents old data sources
     * (which may still be running asynchronously after a context switch) from writing stale
     * data or reading selectors that belong to a different context.
     */
    final class ContextDataManagerView implements TransactionalDataStore, SelectorSource {

        private boolean valid = true;

        void invalidate() {
            valid = false;
        }

        public void init(@NonNull LDContext context, @NonNull Map<String, Flag> items) {
            synchronized (lock) {
                if (!valid) {
                    return;
                }
                initData(context, EnvironmentData.usingExistingFlagsMap(items));
            }
        }

        public boolean upsert(@NonNull LDContext context, @NonNull Flag flag) {
            synchronized (lock) {
                if (!valid) {
                    return false;
                }
                return ContextDataManager.this.upsert(context, flag);
            }
        }

        @Override
        public void apply(@NonNull LDContext context, @NonNull ChangeSet<Map<String, Flag>> changeSet) {
            synchronized (lock) {
                if (!valid) {
                    return;
                }
                ContextDataManager.this.apply(context, changeSet);
            }
        }

        @Override
        @NonNull
        public Selector getSelector() {
            synchronized (lock) {
                if (!valid) {
                    return Selector.EMPTY;
                }
                return ContextDataManager.this.getSelector();
            }
        }
    }
}
