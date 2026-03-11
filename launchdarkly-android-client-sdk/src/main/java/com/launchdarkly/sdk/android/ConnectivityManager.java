package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceState;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.android.subsystems.TransactionalDataStore;

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
    private final Debounce pollDebouncer = new Debounce();
    private final AtomicBoolean forcedOffline = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<DataSource> currentDataSource = new AtomicReference<>();
    private final AtomicReference<LDContext> currentContext = new AtomicReference<>();
    private final AtomicReference<Boolean> previouslyInBackground = new AtomicReference<>();
    private final LDLogger logger;
    private volatile boolean initialized = false;
    private volatile Map<ConnectionMode, ResolvedModeDefinition> resolvedModeTable;
    private volatile ConnectionMode currentFDv2Mode;

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
            // TODO: SDK-1820 DataSource status handling
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
        connectionInformation = new ConnectionInformationState();
        readStoredConnectionState();
        this.backgroundUpdatingDisabled = ldConfig.isDisableBackgroundPolling();

        connectivityChangeListener = networkAvailable -> {
            DataSource dataSource = currentDataSource.get();
            if (dataSource instanceof ModeAware) {
                eventProcessor.setOffline(!networkAvailable);
                resolveAndSwitchMode((ModeAware) dataSource);
            } else {
                updateDataSource(false, LDUtil.noOpCallback());
            }
        };
        platformState.addConnectivityChangeListener(connectivityChangeListener);

        foregroundListener = foreground -> {
            DataSource dataSource = currentDataSource.get();
            if (dataSource instanceof ModeAware) {
                eventProcessor.setInBackground(!foreground);
                resolveAndSwitchMode((ModeAware) dataSource);
            } else if (dataSource == null || dataSource.needsRefresh(!foreground,
                    currentContext.get())) {
                updateDataSource(true, LDUtil.noOpCallback());
            }
        };
        platformState.addForegroundChangeListener(foregroundListener);
    }

    /**
     * Switches the {@link ConnectivityManager} to begin fetching/receiving information
     * relevant to the context provided.  This is likely to result in the teardown of existing
     * connections, but the timing of that is not guaranteed.
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
            if (dataSource == null || dataSource.needsRefresh(!platformState.isForeground(), context)) {
                updateDataSource(true, onCompletion);
            } else {
                onCompletion.onSuccess(null);
            }
        }
    }

    private synchronized boolean updateDataSource(
            boolean mustReinitializeDataSource,
            @NonNull Callback<Void> onCompletion
    ) {
        if (!started.get()) {
            return false;
        }

        boolean forceOffline = forcedOffline.get();
        boolean networkEnabled = platformState.isNetworkAvailable();
        boolean inBackground = !platformState.isForeground();
        LDContext context = currentContext.get();

        eventProcessor.setOffline(forceOffline || !networkEnabled);
        eventProcessor.setInBackground(inBackground);

        boolean shouldStopExistingDataSource = true,
                shouldStartDataSourceIfStopped = false;

        if (forceOffline) {
            logger.debug("Initialized in offline mode");
            initialized = true;
            dataSourceUpdateSink.setStatus(ConnectionInformation.ConnectionMode.SET_OFFLINE, null);
        } else if (!networkEnabled) {
            dataSourceUpdateSink.setStatus(ConnectionInformation.ConnectionMode.OFFLINE, null);
        } else if (inBackground && backgroundUpdatingDisabled) {
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

        logger.debug("Creating data source (background={})", inBackground);
        ClientContext clientContext = ClientContextImpl.forDataSource(
                baseClientContext,
                dataSourceUpdateSink,
                context,
                inBackground,
                previouslyInBackground.get(),
                transactionalDataStore
        );
        DataSource dataSource = dataSourceFactory.build(clientContext);
        currentDataSource.set(dataSource);
        previouslyInBackground.set(Boolean.valueOf(inBackground));

        if (dataSourceFactory instanceof FDv2DataSourceBuilder) {
            FDv2DataSourceBuilder fdv2Builder = (FDv2DataSourceBuilder) dataSourceFactory;
            resolvedModeTable = fdv2Builder.getResolvedModeTable();
            currentFDv2Mode = fdv2Builder.getStartingMode();
        }

        dataSource.start(new Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                initialized = true;
                // passing the current connection mode since we don't want to change the mode, just trigger
                // the logic to update the last connection success.
                updateConnectionInfoForSuccess(connectionInformation.getConnectionMode());
                onCompletion.onSuccess(null);
            }

            @Override
            public void onError(Throwable error) {
                // passing the current connection mode since we don't want to change the mode, just trigger
                // the logic to update the last connection failure.
                updateConnectionInfoForError(connectionInformation.getConnectionMode(), error);
                onCompletion.onSuccess(null);
            }
        });

        // If the app starts in the background, the builder creates the data source with
        // STREAMING as the starting mode. Perform an initial mode resolution to correct this.
        if (dataSource instanceof ModeAware) {
            resolveAndSwitchMode((ModeAware) dataSource);
        }

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
        return updateDataSource(true, onCompletion);
    }

    /**
     * Permanently stops data updating for the current client instance. We call this if the client
     * is being closed, or if we receive an error that indicates the mobile key is invalid.
     */
    void shutDown() {
        if (closed.getAndSet(true)) {
            return;
        }
        DataSource oldDataSource = currentDataSource.getAndSet(null);
        if (oldDataSource != null) {
            oldDataSource.stop(LDUtil.noOpCallback());
        }
        platformState.removeForegroundChangeListener(foregroundListener);
        platformState.removeConnectivityChangeListener(connectivityChangeListener);
    }

    void setForceOffline(boolean forceOffline) {
        boolean wasForcedOffline = forcedOffline.getAndSet(forceOffline);
        if (forceOffline != wasForcedOffline) {
            DataSource dataSource = currentDataSource.get();
            if (dataSource instanceof ModeAware) {
                eventProcessor.setOffline(forceOffline || !platformState.isNetworkAvailable());
                resolveAndSwitchMode((ModeAware) dataSource);
            } else {
                updateDataSource(false, LDUtil.noOpCallback());
            }
        }
    }

    boolean isForcedOffline() {
        return forcedOffline.get();
    }

    /**
     * Resolves the current platform state to a ConnectionMode via the ModeResolutionTable,
     * looks up the ResolvedModeDefinition from the resolved mode table, and calls
     * switchMode() on the data source if the mode has changed.
     */
    private void resolveAndSwitchMode(@NonNull ModeAware modeAware) {
        Map<ConnectionMode, ResolvedModeDefinition> table = resolvedModeTable;
        if (table == null) {
            return;
        }
        boolean forceOffline = forcedOffline.get();
        boolean networkAvailable = platformState.isNetworkAvailable();
        boolean foreground = platformState.isForeground();
        ModeState state = new ModeState(
                foreground && !forceOffline,
                networkAvailable && !forceOffline
        );
        ConnectionMode newMode = ModeResolutionTable.MOBILE.resolve(state);
        if (newMode == currentFDv2Mode) {
            return;
        }
        currentFDv2Mode = newMode;
        ResolvedModeDefinition def = table.get(newMode);
        if (def == null) {
            logger.warn("No resolved definition for mode {}; skipping switchMode", newMode);
            return;
        }
        logger.debug("Switching FDv2 mode to {}", newMode);
        modeAware.switchMode(def);
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
