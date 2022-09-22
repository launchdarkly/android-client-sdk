package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.EventProcessor;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.launchdarkly.sdk.android.ConnectionInformation.ConnectionMode;
import static com.launchdarkly.sdk.android.LDUtil.safeCallbackSuccess;

class ConnectivityManager {

    private static final long MAX_RETRY_TIME_MS = 60_000; // 60 seconds
    private static final long RETRY_TIME_MS = 1_000; // 1 second

    private final ConnectionMode foregroundMode;
    private final ConnectionMode backgroundMode;

    private final PlatformState platformState;
    private final ConnectionInformationState connectionInformation;
    private final PersistentDataStoreWrapper.PerEnvironmentData environmentStore;
    private final StreamUpdateProcessor streamUpdateProcessor;
    private final ContextDataManager contextDataManager;
    private final FeatureFetcher fetcher;
    private final EventProcessor eventProcessor;
    private final Throttler throttler;
    private final PlatformState.ForegroundChangeListener foregroundListener;
    private final TaskExecutor taskExecutor;
    private final int pollingInterval;
    private final int backgroundPollingInterval;
    private final LDUtil.ResultCallback<Void> monitor;
    private final AtomicBoolean setOfflineState;
    private final List<WeakReference<LDStatusListener>> statusListeners = new ArrayList<>();
    private final LDLogger logger;
    private LDUtil.ResultCallback<Void> initCallback = null;
    private volatile boolean initialized = false;

    ConnectivityManager(@NonNull final PlatformState platformState,
                        @NonNull final LDConfig ldConfig,
                        @NonNull final String mobileKey,
                        @NonNull final EventProcessor eventProcessor,
                        @NonNull final ContextDataManager contextDataManager,
                        @NonNull final FeatureFetcher fetcher,
                        @NonNull final PersistentDataStoreWrapper.PerEnvironmentData environmentStore,
                        @NonNull final TaskExecutor taskExecutor,
                        @NonNull final AtomicBoolean setOfflineState,
                        @Nullable final DiagnosticStore diagnosticStore,
                        @NonNull final LDLogger logger) {
        this.platformState = platformState;
        this.eventProcessor = eventProcessor;
        this.contextDataManager = contextDataManager;
        this.fetcher = fetcher;
        this.environmentStore = environmentStore;
        this.taskExecutor = taskExecutor;
        this.setOfflineState = setOfflineState;
        this.logger = logger;
        pollingInterval = ldConfig.getPollingIntervalMillis();
        backgroundPollingInterval = ldConfig.getBackgroundPollingIntervalMillis();
        connectionInformation = new ConnectionInformationState();
        readStoredConnectionState();
        setOfflineState.set(ldConfig.isOffline());

        backgroundMode = ldConfig.isDisableBackgroundPolling() ? ConnectionMode.BACKGROUND_DISABLED : ConnectionMode.BACKGROUND_POLLING;
        foregroundMode = ldConfig.isStream() ? ConnectionMode.STREAMING : ConnectionMode.POLLING;

        throttler = new Throttler(() -> {
            synchronized (ConnectivityManager.this) {
                attemptTransition(platformState.isForeground() ? foregroundMode : backgroundMode);
            }
        }, RETRY_TIME_MS, MAX_RETRY_TIME_MS);

        foregroundListener = new PlatformState.ForegroundChangeListener() {
            @Override
            public void onForegroundChanged(boolean foreground) {
                synchronized (ConnectivityManager.this) {
                    if (foreground) {
                        if (platformState.isNetworkAvailable() && !isOffline() &&
                                connectionInformation.getConnectionMode() != foregroundMode) {
                            throttler.attemptRun();
                            eventProcessor.setInBackground(false);
                        }
                    } else {
                        if (platformState.isNetworkAvailable() && !isOffline() &&
                                connectionInformation.getConnectionMode() != backgroundMode) {
                            throttler.cancel();
                            eventProcessor.setInBackground(true);
                            attemptTransition(backgroundMode);
                        }
                    }
                }
            }
        };

        monitor = new LDUtil.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                synchronized (ConnectivityManager.this) {
                    initialized = true;
                    connectionInformation.setLastSuccessfulConnection(getCurrentTimestamp());
                    saveConnectionInformation();
                    callInitCallback();
                }
            }

            @Override
            public void onError(Throwable e) {
                synchronized (ConnectivityManager.this) {
                    connectionInformation.setLastFailedConnection(getCurrentTimestamp());
                    if (e instanceof LDFailure) {
                        connectionInformation.setLastFailure((LDFailure) e);
                    } else {
                        connectionInformation.setLastFailure(new LDFailure("Unknown failure", e, LDFailure.FailureType.UNKNOWN_ERROR));
                    }
                    saveConnectionInformation();
                    updateListenersOnFailure(connectionInformation.getLastFailure());
                    callInitCallback();
                }
            }
        };

        streamUpdateProcessor = ldConfig.isStream() ? new StreamUpdateProcessor(platformState, ldConfig,
                contextDataManager, fetcher, this, mobileKey, diagnosticStore, monitor, logger) : null;
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

    private void callInitCallback() {
        LDUtil.safeCallbackSuccess(initCallback, null);
        initCallback = null;
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

    private synchronized void saveConnectionInformation() {
        PersistentDataStoreWrapper.SavedConnectionInfo savedConnectionInfo =
                new PersistentDataStoreWrapper.SavedConnectionInfo(
                        connectionInformation.getLastSuccessfulConnection(),
                        connectionInformation.getLastFailedConnection(),
                        connectionInformation.getLastFailure());
        environmentStore.setConnectionInfo(savedConnectionInfo);
    }

    private void updateListenersConnectionModeChanged(final ConnectionInformation connectionInformation) {
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

    private void stopPolling() {
        PollingUpdater.stop(taskExecutor);
    }

    private void startPolling() {
        triggerPoll();
        PollingUpdater.startPolling(taskExecutor, pollingInterval, pollingInterval);
    }

    private void startBackgroundPolling() {
        if (initCallback != null) {
            initCallback.onSuccess(null);
            initCallback = null;
        }
        PollingUpdater.startPolling(taskExecutor, backgroundPollingInterval, backgroundPollingInterval);
    }

    private void stopStreaming() {
        if (streamUpdateProcessor != null) {
            streamUpdateProcessor.stop(null);
        }
    }

    private void stopStreaming(final LDUtil.ResultCallback<Void> onCompleteListener) {
        if (streamUpdateProcessor != null) {
            streamUpdateProcessor.stop(onCompleteListener);
        } else {
            safeCallbackSuccess(onCompleteListener, null);
        }
    }

    private void startStreaming() {
        if (streamUpdateProcessor != null) {
            streamUpdateProcessor.start();
        }
    }

    private void removeForegroundListener() {
        platformState.removeForegroundChangeListener(foregroundListener);
    }

    private void addForegroundListener() {
        platformState.removeForegroundChangeListener(foregroundListener);
        platformState.addForegroundChangeListener(foregroundListener);
    }

    private void removeNetworkListener() {
        // For now these don't do anything, but will later dynamically register and unregister
        // the network connectivity receiver
    }

    private void addNetworkListener() {
        // For now these don't do anything, but will later dynamically register and unregister
        // the network connectivity receiver
    }

    private synchronized void attemptTransition(ConnectionMode nextState) {
        if (nextState.isTransitionOnForeground()) {
            addForegroundListener();
        } else {
            removeForegroundListener();
        }
        if (nextState.isTransitionOnNetwork()) {
            addNetworkListener();
        } else {
            removeNetworkListener();
        }

        switch (nextState) {
            case SHUTDOWN:
            case BACKGROUND_DISABLED:
            case SET_OFFLINE:
            case OFFLINE:
                initialized = true;
                callInitCallback();
                stopPolling();
                stopStreaming();
                break;
            case STREAMING:
                initialized = false;
                stopPolling();
                startStreaming();
                break;
            case POLLING:
                initialized = false;
                stopPolling();
                startPolling();
                break;
            case BACKGROUND_POLLING:
                initialized = true;
                callInitCallback();
                stopStreaming();
                stopPolling();
                startBackgroundPolling();
                break;
        }

        updateConnectionMode(nextState);
    }

    /**
     * Attempts to start the data source if possible.
     * <p>
     * If we are configured to be offline or the network is unavailable, it immediately calls the
     * completion listener and returns. Otherwise, it continues initialization asynchronously and
     * the listener will be called when the data source successfully starts up or permanently fails.
     * <p>
     * The return value is true if we are online, or false if we are offline (this determines
     * whether we should try to send an identify event on startup).
     */
    synchronized boolean startUp(LDUtil.ResultCallback<Void> onCompleteListener) {
        initialized = false;

        final LDContext context = contextDataManager.getCurrentContext();

        // Calling initFromStoredData updates the current flag state *if* stored flags exist for
        // this context. If they don't, it has no effect. Currently we do *not* return early from
        // initialization just because stored flags exist; we're just making them available in case
        // initialization times out or otherwise fails.
        contextDataManager.initFromStoredData(context);

        if (isOffline()) {
            logger.debug("Initialized in offline mode");
            initialized = true;
            updateConnectionMode(ConnectionMode.SET_OFFLINE);
            safeCallbackSuccess(onCompleteListener, null);
            return false;
        }

        final boolean connected = platformState.isNetworkAvailable();

        if (!connected) {
            initialized = true;
            updateConnectionMode(ConnectionMode.OFFLINE);
            safeCallbackSuccess(onCompleteListener, null);
            return false;
        }

        initCallback = onCompleteListener;
        eventProcessor.setOffline(false);
        throttler.attemptRun();
        return true;
    }

    synchronized void shutdown() {
        throttler.cancel();
        updateConnectionMode(ConnectionMode.SHUTDOWN);
        removeForegroundListener();
        removeNetworkListener();
        stopStreaming();
        stopPolling();
        setOfflineState.set(true);
        eventProcessor.setOffline(true);
        callInitCallback();
    }

    synchronized void setOnline() {
        if (setOfflineState.getAndSet(false)) {
            startUp(null);
        }
    }

    synchronized void setOffline() {
        if (!setOfflineState.getAndSet(true)) {
            throttler.cancel();
            attemptTransition(ConnectionMode.SET_OFFLINE);
            eventProcessor.setOffline(true);
        }
    }

    boolean isOffline() {
        return setOfflineState.get();
    }

    synchronized void reloadData(final LDUtil.ResultCallback<Void> onCompleteListener) {
        throttler.cancel();
        callInitCallback();
        removeForegroundListener();
        removeNetworkListener();
        stopPolling();
        stopStreaming(new LDUtil.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                startUp(onCompleteListener);
            }

            @Override
            public void onError(Throwable e) {
                startUp(onCompleteListener);
            }
        });
    }

    private synchronized void updateConnectionMode(ConnectionMode connectionMode) {
        if (connectionInformation.getConnectionMode() == ConnectionMode.STREAMING && initialized) {
            connectionInformation.setLastSuccessfulConnection(getCurrentTimestamp());
        }
        connectionInformation.setConnectionMode(connectionMode);
        try {
            saveConnectionInformation();
        } catch (Exception ex) {
            LDUtil.logExceptionAtErrorLevel(logger, ex, "Error saving connection information");
        }
        updateListenersConnectionModeChanged(connectionInformation);
    }

    synchronized void onNetworkConnectivityChange(boolean connectedToInternet) {
        if (isOffline()) {
            // abort if manually set offline
            return;
        }
        if (connectionInformation.getConnectionMode() == ConnectionMode.OFFLINE && connectedToInternet) {
            eventProcessor.setOffline(false);
            throttler.attemptRun();
        } else if (connectionInformation.getConnectionMode() != ConnectionMode.OFFLINE && !connectedToInternet) {
            eventProcessor.setOffline(true);
            throttler.cancel();
            attemptTransition(ConnectionMode.OFFLINE);
        }
    }

    private long getCurrentTimestamp() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTimeInMillis();
    }

    synchronized ConnectionInformation getConnectionInformation() {
        if (connectionInformation.getConnectionMode() == ConnectionMode.STREAMING && initialized) {
            connectionInformation.setLastSuccessfulConnection(getCurrentTimestamp());
            saveConnectionInformation();
        }
        return connectionInformation;
    }

    void triggerPoll() {
        if (!isOffline()) {
            PollingUpdater.triggerPoll(platformState, contextDataManager, fetcher, monitor, logger);
        }
    }
}
