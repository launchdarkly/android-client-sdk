package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.integrations.AutomaticModeSwitchingConfig;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceState;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.android.subsystems.TransactionalDataStore;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class ConnectivityManager {
    // Implementation notes:
    //
    // 1. This class has no direct interactions with Android APIs. All logic related to detecting
    // system state, such as network connectivity and foreground/background, is done through the
    // PlatformState abstraction; all logic related to task scheduling is done through the
    // TaskExecutor abstraction.
    //
    // 2. Each instance of this class belongs to a single LDClient instance. So in multi-environment
    // mode, there will be several of these, one for each client.
    //
    // 3. Whenever there is a state change that requires new data source behavior to be changed
    // (e.g. starting a stream for the first time, restarting a stream for a new context, or
    // switching between foreground and background), the existing data source if any is stopped,
    // and a new data source is created and started.
    //
    // 4. Whenever there is a state change that requires us to be offline (e.g. set offline in
    // configuration, or network unavailable, or we're in the background and background updating is
    // disabled), the existing data source if any is stopped.
    //
    // This class does not know anything about data source details such as streaming/polling; those
    // are handled by the configured data source factory.

    private static final long MAX_RETRY_TIME_MS = 60_000; // 60 seconds
    private static final long RETRY_TIME_MS = 1_000; // 1 second

    private final ClientContext baseClientContext;
    private final PlatformState platformState;
    private final ComponentConfigurer<DataSource> dataSourceFactory;
    private final DataSourceUpdateSink dataSourceUpdateSink;
    private final ConnectionInformationState connectionInformation;
    private final PersistentDataStoreWrapper.PerEnvironmentData environmentStore;
    private final EventProcessor eventProcessor;
    private final TransactionalDataStore transactionalDataStore;
    private final PlatformState.ForegroundChangeListener foregroundListener;
    private final PlatformState.ConnectivityChangeListener connectivityChangeListener;
    private final TaskExecutor taskExecutor;
    private final boolean backgroundUpdatingDisabled;
    private final List<WeakReference<LDStatusListener>> statusListeners = new ArrayList<>();
    private final Debounce pollDebouncer = new Debounce(); // FDv1 only
    private final AtomicBoolean forcedOffline = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<DataSource> currentDataSource = new AtomicReference<>();
    private final AtomicReference<LDContext> currentContext = new AtomicReference<>();
    private final AtomicReference<ModeState> previousModeState = new AtomicReference<>();
    private final LDLogger logger;
    private volatile boolean initialized = false;
    private final boolean useFDv2ModeResolution;
    private final ModeResolutionTable modeResolutionTable;
    private volatile ConnectionMode currentFDv2Mode;
    private final AutomaticModeSwitchingConfig autoModeSwitchingConfig;
    private final long connectionModeStateDebounceMs; // visible for testing
    private volatile StateDebounceManager stateDebounceManager;

    // The DataSourceUpdateSinkImpl receives flag updates and status updates from the DataSource.
    // This has two purposes: 1. to decouple the data source implementation from the details of how
    // data is stored; 2. to implement additional logic that does not depend on what kind of data
    // source we're using, like "if there was an error, update the ConnectionInformation."
    private class DataSourceUpdateSinkImpl implements DataSourceUpdateSink, DataSourceUpdateSinkV2 {
        private final ContextDataManager contextDataManager;

        DataSourceUpdateSinkImpl(ContextDataManager contextDataManager) {
            this.contextDataManager = contextDataManager;
        }

        @Override
        public void init(LDContext context, Map<String, DataModel.Flag> items) {
            contextDataManager.initData(context, EnvironmentData.usingExistingFlagsMap(items));
            // Currently, contextDataManager is responsible for firing any necessary flag change events.
        }

        @Override
        public void upsert(LDContext context, DataModel.Flag item) {
            contextDataManager.upsert(context, item);
            // Currently, contextDataManager is responsible for firing any necessary flag change events.
        }

        @Override
        public void apply(@NonNull LDContext context, @NonNull ChangeSet<Map<String, DataModel.Flag>> changeSet) {
            contextDataManager.apply(context, changeSet);
            // Currently, contextDataManager is responsible for firing any necessary flag change events.
        }

        @Override
        public void setStatus(ConnectionInformation.ConnectionMode newConnectionMode, Throwable error) {
            if (error == null) {
                updateConnectionInfoForSuccess(newConnectionMode);
            } else {
                updateConnectionInfoForError(newConnectionMode, error);
            }
        }

        @Override
        public void setStatus(@NonNull DataSourceState state, Throwable failure) {
            // TODO: SDK-1820 — this is a temporary implementation to support e2e tests
            ConnectionInformation.ConnectionMode publicMode = mapFDv2ToPublicMode(state);
            if (publicMode == null) {
                return;
            }
            if (failure == null) {
                updateConnectionInfoForSuccess(publicMode);
            } else {
                updateConnectionInfoForError(publicMode, failure);
            }
        }

        @Override
        public void shutDown() {
            // The DataSource will call this method if it receives an error such as HTTP 401 that
            // indicates the mobile key is invalid.
            ConnectivityManager.this.shutDown();
            setStatus(ConnectionInformation.ConnectionMode.SHUTDOWN, null);
        }
    }

    ConnectivityManager(@NonNull final ClientContext clientContext,
                        @NonNull final ComponentConfigurer<DataSource> dataSourceFactory,
                        @NonNull final EventProcessor eventProcessor,
                        @NonNull final ContextDataManager contextDataManager,
                        @NonNull final PersistentDataStoreWrapper.PerEnvironmentData environmentStore
    ) {
        this.baseClientContext = clientContext;
        this.dataSourceFactory = dataSourceFactory;
        this.dataSourceUpdateSink = new DataSourceUpdateSinkImpl(contextDataManager);
        this.platformState = ClientContextImpl.get(clientContext).getPlatformState();
        this.eventProcessor = eventProcessor;
        this.environmentStore = environmentStore;
        this.transactionalDataStore = contextDataManager;
        this.taskExecutor = ClientContextImpl.get(clientContext).getTaskExecutor();
        this.logger = clientContext.getBaseLogger();

        currentContext.set(clientContext.getEvaluationContext());
        forcedOffline.set(clientContext.isSetOffline());

        LDConfig ldConfig = clientContext.getConfig();
        this.connectionModeStateDebounceMs = ldConfig.getConnectionModeStateDebounceMs();
        connectionInformation = new ConnectionInformationState();
        readStoredConnectionState();
        this.backgroundUpdatingDisabled = ldConfig.isDisableBackgroundPolling();
        this.autoModeSwitchingConfig = ldConfig.getAutomaticModeSwitchingConfig();
        this.useFDv2ModeResolution = (dataSourceFactory instanceof FDv2DataSourceBuilder);
        this.modeResolutionTable = useFDv2ModeResolution
                ? ((FDv2DataSourceBuilder) dataSourceFactory).getResolutionTable()
                : null;

        this.stateDebounceManager = createDebounceManager();

        connectivityChangeListener = networkAvailable -> {
            updateEventProcessor(forcedOffline.get(), platformState.isNetworkAvailable(), platformState.isForeground());
            if (!autoModeSwitchingConfig.isNetwork()) {
                return;
            }
            stateDebounceManager.setNetworkAvailable(networkAvailable);
        };
        platformState.addConnectivityChangeListener(connectivityChangeListener);

        foregroundListener = foreground -> {
            updateEventProcessor(forcedOffline.get(), platformState.isNetworkAvailable(), platformState.isForeground());
            if (!autoModeSwitchingConfig.isLifecycle()) {
                return;
            }
            // CONNMODE 3.3.1: flush pending events before transitioning to background
            if (useFDv2ModeResolution && !foreground) {
                eventProcessor.flush();
            }
            stateDebounceManager.setForeground(foreground);
        };
        platformState.addForegroundChangeListener(foregroundListener);
    }

    /**
     * Switches the {@link ConnectivityManager} to begin fetching/receiving information
     * relevant to the context provided.  This is likely to result in the teardown of existing
     * connections, but the timing of that is not guaranteed.
     * <p>
     * CONNMODE 3.5.6: identify does NOT participate in debounce. The debounce manager is
     * destroyed and recreated so that any pending debounced state change is discarded and
     * the new context starts with a clean timer.
     *
     * @param context to swtich to
     * @param onCompletion callback that indicates when the switching is done
     */
    void switchToContext(@NonNull LDContext context, @NonNull Callback<Void> onCompletion) {
        DataSource dataSource = currentDataSource.get();
        LDContext oldContext = currentContext.getAndSet(context);

        if (oldContext == context || oldContext.equals(context)) {
            onCompletion.onSuccess(null);
        } else {
            // CONNMODE 3.5.6: identify bypasses debounce — close and recreate the manager
            stateDebounceManager.close();
            stateDebounceManager = createDebounceManager();

            ModeState state = snapshotModeState();
            if (dataSource == null || dataSource.needsRefresh(!state.isForeground(), context)) {
                updateEventProcessor(forcedOffline.get(), state.isNetworkAvailable(), state.isForeground());
                updateDataSource(true, state, onCompletion);
            } else {
                onCompletion.onSuccess(null);
            }
        }
    }

    private synchronized boolean updateDataSource(
            boolean mustReinitializeDataSource,
            @NonNull ModeState newState,
            @NonNull Callback<Void> onCompletion
    ) {
        if (!started.get()) {
            return false;
        }

        DataSource existingDataSource = currentDataSource.get();
        boolean isFDv2ModeSwitch = false;
        
        // FDv2 path: resolve mode for both startup (mustReinitializeDataSource=true) and
        // state-change (mustReinitializeDataSource=false) cases.
        if (useFDv2ModeResolution) {
            ConnectionMode newMode = resolveMode(newState);
            if (!mustReinitializeDataSource) {
                // State-change path: check for no-op or equivalent config before rebuilding.
                if (newMode == currentFDv2Mode) {
                    onCompletion.onSuccess(null);
                    return false;
                }
                // CSFDV2 5.3.8: retain active data source if old and new modes have equivalent config.
                // ModeDefinition currently relies on Object.equals (reference equality) because
                // makeDefaultModeTable() reuses the same instance for modes that share identical
                // configuration.
                FDv2DataSourceBuilder fdv2Builder = (FDv2DataSourceBuilder) dataSourceFactory;
                ModeDefinition oldDef = fdv2Builder.getModeDefinition(currentFDv2Mode);
                ModeDefinition newDef = fdv2Builder.getModeDefinition(newMode);
                if (oldDef != null && oldDef.equals(newDef)) {
                    currentFDv2Mode = newMode;
                    onCompletion.onSuccess(null);
                    return false;
                }
                isFDv2ModeSwitch = true;
                mustReinitializeDataSource = true;
            }
            currentFDv2Mode = newMode;
        }

        // Only consult needsRefresh() when the platform state has actually changed since the
        // last data source was built. Duplicate notifications (e.g. a connectivity event that
        // doesn't change the network state) are filtered out, preventing unnecessary rebuilds.
        // Context changes are handled by switchToContext(), which passes
        // mustReinitializeDataSource=true directly.
        if (!mustReinitializeDataSource && existingDataSource != null) {
            boolean inBackground = !newState.isForeground();
            ModeState prevState = previousModeState.get();
            if (prevState != null && !prevState.equals(newState)) {
                if (existingDataSource.needsRefresh(inBackground, currentContext.get())) {
                    mustReinitializeDataSource = true;
                }
            }
        }

        boolean forceOffline = forcedOffline.get();
        LDContext context = currentContext.get();

        boolean shouldStopExistingDataSource = true,
                shouldStartDataSourceIfStopped = false;

        if (useFDv2ModeResolution) {
            // FDv2 mode resolution already accounts for offline/background states via
            // the ModeResolutionTable, so we always rebuild when the mode changed.
            // Eagerly set the public ConnectionMode so getConnectionInformation() is
            // never null — mirrors what FDv1 data source builders do in build().
            // TODO: SDK-1820 — this is a temporary implementation
            connectionInformation.setConnectionMode(fdv2ModeToPublicMode(currentFDv2Mode));
            shouldStopExistingDataSource = mustReinitializeDataSource;
            shouldStartDataSourceIfStopped = true;
        } else if (forceOffline) {
            logger.debug("Initialized in offline mode");
            initialized = true;
            dataSourceUpdateSink.setStatus(ConnectionInformation.ConnectionMode.SET_OFFLINE, null);
        } else if (!newState.isNetworkAvailable()) {
            dataSourceUpdateSink.setStatus(ConnectionInformation.ConnectionMode.OFFLINE, null);
        } else if (!newState.isForeground() && newState.isBackgroundUpdatingDisabled()) {
            dataSourceUpdateSink.setStatus(ConnectionInformation.ConnectionMode.BACKGROUND_DISABLED, null);
        } else {
            shouldStopExistingDataSource = mustReinitializeDataSource;
            shouldStartDataSourceIfStopped = true;
        }

        if (shouldStopExistingDataSource) {
            DataSource oldDataSource = currentDataSource.getAndSet(null);
            if (oldDataSource != null) {
                logger.debug("Stopping current data source");
                oldDataSource.stop(LDUtil.noOpCallback());
            }
        }
        if (!shouldStartDataSourceIfStopped || currentDataSource.get() != null) {
            onCompletion.onSuccess(null);
            return false;
        }

        logger.debug("Creating data source (background={})", !newState.isForeground());
        ClientContext clientContext = ClientContextImpl.forDataSource(
                baseClientContext,
                dataSourceUpdateSink,
                context,
                !newState.isForeground(),
                previousModeState.get() != null ? !previousModeState.get().isForeground() : null,
                transactionalDataStore
        );

        if (useFDv2ModeResolution) {
            // CONNMODE 2.0.1: mode switches only transition synchronizers, not initializers.
            // TODO: SDK-2071 - refactor running initializers to use existence of selector
            ((FDv2DataSourceBuilder) dataSourceFactory).setActiveMode(currentFDv2Mode, !isFDv2ModeSwitch);
        }

        DataSource dataSource = dataSourceFactory.build(clientContext);
        currentDataSource.set(dataSource);
        previousModeState.set(newState);

        dataSource.start(new Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                initialized = true;
                updateConnectionInfoForSuccess(connectionInformation.getConnectionMode());
                onCompletion.onSuccess(null);
            }

            @Override
            public void onError(Throwable error) {
                updateConnectionInfoForError(connectionInformation.getConnectionMode(), error);
                onCompletion.onSuccess(null);
            }
        });

        return true;
    }

    boolean isInitialized() {
        return initialized;
    }

    void registerStatusListener(LDStatusListener LDStatusListener) {
        if (LDStatusListener == null) {
            return;
        }
        synchronized (statusListeners) {
            statusListeners.add(new WeakReference<>(LDStatusListener));
        }
    }

    void unregisterStatusListener(LDStatusListener LDStatusListener) {
        if (LDStatusListener == null) {
            return;
        }
        synchronized (statusListeners) {
            Iterator<WeakReference<LDStatusListener>> iter = statusListeners.iterator();
            while (iter.hasNext()) {
                LDStatusListener mListener = iter.next().get();
                if (mListener == null || mListener == LDStatusListener) {
                    iter.remove();
                }
            }
        }
    }

    private void updateConnectionInfoForSuccess(ConnectionInformation.ConnectionMode connectionMode) {
        boolean updated = false;
        if (connectionInformation.getConnectionMode() != connectionMode) {
            connectionInformation.setConnectionMode(connectionMode);
            updated = true;
        }

        // even if connection mode doesn't change, it may be the case that the data source re-established its connection
        // and so we should update the last successful connection time (e.g. connection drops and we reconnect,
        // an identify occurs)
        if (connectionMode.isConnectionActive()) {
            connectionInformation.setLastSuccessfulConnection(System.currentTimeMillis());
            updated = true;
        }

        if (updated) {
            try {
                saveConnectionInformation(connectionInformation);
            } catch (Exception ex) {
                LDUtil.logExceptionAtErrorLevel(logger, ex, "Error saving connection information");
            }
            updateStatusListeners(connectionInformation);
        }
    }

    private void updateConnectionInfoForError(ConnectionInformation.ConnectionMode connectionMode, Throwable error) {
        LDFailure failure = null;
        if (error != null) {
            if (error instanceof LDFailure) {
                failure = (LDFailure)error;
            } else {
                failure = new LDFailure("Unknown failure", error, LDFailure.FailureType.UNKNOWN_ERROR);
            }
        }

        connectionInformation.setConnectionMode(connectionMode);
        connectionInformation.setLastFailedConnection(System.currentTimeMillis());
        connectionInformation.setLastFailure(failure);
        try {
            saveConnectionInformation(connectionInformation);
        } catch (Exception ex) {
            LDUtil.logExceptionAtErrorLevel(logger, ex, "Error saving connection information");
        }
        updateStatusListeners(connectionInformation);
    }

    /**
     * TODO: SDK-1820 — this is a temporary implementation to support e2e tests
     */
    private ConnectionInformation.ConnectionMode mapFDv2ToPublicMode(DataSourceState state) {
        switch (state) {
            case VALID:
                return fdv2ModeToPublicMode(currentFDv2Mode);
            case INTERRUPTED:
                ConnectionInformation.ConnectionMode current = connectionInformation.getConnectionMode();
                return current != null ? current : fdv2ModeToPublicMode(currentFDv2Mode);
            case OFF:
                return ConnectionInformation.ConnectionMode.OFFLINE;
            case INITIALIZING:
            default:
                return null;
        }
    }

    /**
     * TODO: SDK-1820 — this is a temporary implementation to support e2e tests
     */
    private static ConnectionInformation.ConnectionMode fdv2ModeToPublicMode(ConnectionMode mode) {
        if (mode == null) {
            return ConnectionInformation.ConnectionMode.POLLING;
        }
        if (mode == ConnectionMode.STREAMING) {
            return ConnectionInformation.ConnectionMode.STREAMING;
        } else if (mode == ConnectionMode.POLLING || mode == ConnectionMode.ONE_SHOT) {
            return ConnectionInformation.ConnectionMode.POLLING;
        } else if (mode == ConnectionMode.BACKGROUND) {
            return ConnectionInformation.ConnectionMode.BACKGROUND_POLLING;
        } else if (mode == ConnectionMode.OFFLINE) {
            return ConnectionInformation.ConnectionMode.OFFLINE;
        }
        return ConnectionInformation.ConnectionMode.POLLING;
    }

    private void readStoredConnectionState() {
        PersistentDataStoreWrapper.SavedConnectionInfo savedConnectionInfo =
                environmentStore.getConnectionInfo();
        Long lastSuccessTime = savedConnectionInfo.lastSuccessTime;
        Long lastFailureTime = savedConnectionInfo.lastFailureTime;
        connectionInformation.setLastSuccessfulConnection(lastSuccessTime == null || lastSuccessTime.longValue() == 0 ?
                null : lastSuccessTime.longValue());
        connectionInformation.setLastFailedConnection(lastFailureTime == null || lastFailureTime.longValue() == 0 ?
                null : lastFailureTime.longValue());
        connectionInformation.setLastFailure(savedConnectionInfo.lastFailure);
    }

    private synchronized void saveConnectionInformation(ConnectionInformation connectionInformation) {
        PersistentDataStoreWrapper.SavedConnectionInfo savedConnectionInfo =
                new PersistentDataStoreWrapper.SavedConnectionInfo(
                        connectionInformation.getLastSuccessfulConnection(),
                        connectionInformation.getLastFailedConnection(),
                        connectionInformation.getLastFailure());
        environmentStore.setConnectionInfo(savedConnectionInfo);
    }

    private void updateStatusListeners(final ConnectionInformation connectionInformation) {
        synchronized (statusListeners) {
            Iterator<WeakReference<LDStatusListener>> iter = statusListeners.iterator();
            while (iter.hasNext()) {
                final LDStatusListener mListener = iter.next().get();
                if (mListener == null) {
                    iter.remove();
                } else {
                    taskExecutor.scheduleTask(() -> mListener.onConnectionModeChanged(connectionInformation), 0);
                }
            }
        }
    }

    private void updateListenersOnFailure(final LDFailure ldFailure) {
        synchronized (statusListeners) {
            Iterator<WeakReference<LDStatusListener>> iter = statusListeners.iterator();
            while (iter.hasNext()) {
                final LDStatusListener mListener = iter.next().get();
                if (mListener == null) {
                    iter.remove();
                } else {
                    taskExecutor.scheduleTask(() -> mListener.onInternalFailure(ldFailure), 0);
                }
            }
        }
    }

    /**
     * Attempts to start the data source if possible.
     * <p>
     * If we are configured to be offline or the network is unavailable, it immediately calls the
     * completion listener and returns. Otherwise, it continues initialization asynchronously and
     * the listener will be called when the data source successfully starts up or permanently fails.
     *
     * @return true if we are online, or false if we are offline (this determines whether we should
     *  try to send an identify event on startup)
     */
    synchronized boolean startUp(@NonNull Callback<Void> onCompletion) {
        if (closed.get() || started.getAndSet(true)) {
            return false;
        }
        initialized = false;

        ModeState state = snapshotModeState();
        updateEventProcessor(forcedOffline.get(), state.isNetworkAvailable(), state.isForeground());
        return updateDataSource(true, state, onCompletion);
    }

    /**
     * Permanently stops data updating for the current client instance. We call this if the client
     * is being closed, or if we receive an error that indicates the mobile key is invalid.
     */
    void shutDown() {
        if (closed.getAndSet(true)) {
            return;
        }
        stateDebounceManager.close();
        stateDebounceManager = null;
        DataSource oldDataSource = currentDataSource.getAndSet(null);
        if (oldDataSource != null) {
            oldDataSource.stop(LDUtil.noOpCallback());
        }
        if (dataSourceFactory instanceof Closeable) {
            try {
                ((Closeable) dataSourceFactory).close();
            } catch (IOException ignored) {
            }
        }
        platformState.removeForegroundChangeListener(foregroundListener);
        platformState.removeConnectivityChangeListener(connectivityChangeListener);
    }

    // Intentionally bypasses the debounce manager. setForceOffline is a legacy
    // API that predates FDv2 and must remain immediate for backward compatibility.
    // This is safe because resolveMode() short-circuits to OFFLINE when forcedOffline
    // is set, so any in-flight debounced callback will resolve to the same mode and no-op.
    void setForceOffline(boolean forceOffline) {
        boolean wasForcedOffline = forcedOffline.getAndSet(forceOffline);
        if (forceOffline != wasForcedOffline) {
            updateEventProcessor(forceOffline, platformState.isNetworkAvailable(), platformState.isForeground());
            handleModeStateChange();
        }
    }

    boolean isForcedOffline() {
        return forcedOffline.get();
    }

    private void updateEventProcessor(boolean forceOffline, boolean networkAvailable, boolean foreground) {
        eventProcessor.setOffline(forceOffline || !networkAvailable);
        eventProcessor.setInBackground(!foreground);
    }

    /**
     * FDv1 reconciliation callback. Invoked by the {@link StateDebounceManager} in immediate
     * mode ({@code debounceMs=0}). Does NOT call {@link #updateEventProcessor} because
     * that is already done in the listener lambdas before the debounce manager fires.
     * <p>
     * Also called directly by {@link #setForceOffline(boolean)}, which handles its own
     * event processor update before calling this.
     */
    private synchronized void handleModeStateChange() {
        ModeState state = snapshotModeState();
        updateDataSource(false, state, LDUtil.noOpCallback());
    }

    /**
     * Creates a new {@link StateDebounceManager} initialized with the current platform state.
     * <p>
     * For FDv2, uses the configured debounce window so rapid state changes are coalesced
     * (CONNMODE 3.5). For FDv1, uses {@code debounceMs=0} ("immediate mode") so the
     * reconcile callback fires synchronously on each state change — matching the pre-existing
     * FDv1 behavior while sharing the same code path.
     * <p>
     * Called once during construction and again on each identify to discard pending
     * debounced changes (CONNMODE 3.5.6).
     */
    private StateDebounceManager createDebounceManager() {
        long effectiveDebounceMs = useFDv2ModeResolution ? connectionModeStateDebounceMs : 0;
        Runnable reconcileCallback = useFDv2ModeResolution
                ? this::handleDebouncedModeStateChange
                : this::handleModeStateChange;
        return new StateDebounceManager(
                platformState.isNetworkAvailable(),
                platformState.isForeground(),
                taskExecutor,
                effectiveDebounceMs,
                reconcileCallback
        );
    }

    /**
     * Reconciliation callback invoked by the {@link StateDebounceManager} when the debounce
     * timer fires (CONNMODE 3.5.3). Reads the latest accumulated state from the debounce
     * manager and triggers a data source update if the resolved mode has changed.
     */
    private void handleDebouncedModeStateChange() {
        ModeState state = new ModeState(
                stateDebounceManager.isForeground(),
                stateDebounceManager.isNetworkAvailable(),
                backgroundUpdatingDisabled
        );
        updateDataSource(false, state, LDUtil.noOpCallback());
    }

    private ModeState snapshotModeState() {
        return new ModeState(
            platformState.isForeground(),
            platformState.isNetworkAvailable(),
            backgroundUpdatingDisabled
        );
    }

    /**
     * Resolves the current platform state to a {@link ConnectionMode} via the
     * {@link ModeResolutionTable}. Force-offline is handled as a short-circuit
     * so that {@link ModeState} faithfully represents actual platform state.
     */
    private ConnectionMode resolveMode(ModeState state) {
        if (forcedOffline.get()) {
            return ConnectionMode.OFFLINE;
        }
        return modeResolutionTable.resolve(state);
    }

    synchronized ConnectionInformation getConnectionInformation() {
        return connectionInformation;
    }

    static void fetchAndSetData(
            FeatureFetcher fetcher,
            LDContext contextToFetch,
            DataSourceUpdateSink dataSourceUpdateSink,
            Callback<Boolean> resultCallback,
            LDLogger logger
    ) {
        fetcher.fetch(contextToFetch, new Callback<String>() {
            @Override
            public void onSuccess(String flagsJson) {
                EnvironmentData data;
                try {
                    data = EnvironmentData.fromJson(flagsJson);
                } catch (Exception e) {
                    logger.debug("Received invalid JSON flag data: {}", flagsJson);
                    resultCallback.onError(new LDFailure("Invalid JSON received from flags endpoint",
                            e, LDFailure.FailureType.INVALID_RESPONSE_BODY));
                    return;
                }
                dataSourceUpdateSink.init(contextToFetch, data.getAll());
                resultCallback.onSuccess(true);
            }

            @Override
            public void onError(Throwable e) {
                logger.error("Error when attempting to get flag data: [{}] [{}]: {}",
                        LDUtil.urlSafeBase64(contextToFetch),
                        contextToFetch,
                        LogValues.exceptionSummary(e));
                resultCallback.onError(e);
            }
        });
    }
}
