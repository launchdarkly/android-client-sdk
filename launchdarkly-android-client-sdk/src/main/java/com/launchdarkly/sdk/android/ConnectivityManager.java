package com.launchdarkly.sdk.android;

import android.app.Application;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.EventProcessor;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static com.launchdarkly.sdk.android.ConnectionInformation.ConnectionMode;
import static com.launchdarkly.sdk.android.LDUtil.isInternetConnected;
import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

class ConnectivityManager {

    private static final long MAX_RETRY_TIME_MS = 60_000; // 60 seconds
    private static final long RETRY_TIME_MS = 1_000; // 1 second

    private final ConnectionMode foregroundMode;
    private final ConnectionMode backgroundMode;

    private final Application application;
    private final ConnectionInformationState connectionInformation;
    private final PersistentDataStore store;
    private final String storeNamespace;
    private final StreamUpdateProcessor streamUpdateProcessor;
    private final ContextManager contextManager;
    private final EventProcessor eventProcessor;
    private final Throttler throttler;
    private final Foreground.Listener foregroundListener;
    private final String environmentName;
    private final int pollingInterval;
    private final LDUtil.ResultCallback<Void> monitor;
    private final LDLogger logger;
    private LDUtil.ResultCallback<Void> initCallback = null;
    private volatile boolean initialized = false;
    private volatile boolean setOffline;

    ConnectivityManager(@NonNull final Application application,
                        @NonNull final LDConfig ldConfig,
                        @NonNull final EventProcessor eventProcessor,
                        @NonNull final ContextManager contextManager,
                        @NonNull final PersistentDataStore store,
                        @NonNull final String environmentName,
                        final DiagnosticStore diagnosticStore,
                        final LDLogger logger) {
        this.application = application;
        this.eventProcessor = eventProcessor;
        this.contextManager = contextManager;
        this.store = store;
        this.environmentName = environmentName;
        this.logger = logger;
        pollingInterval = ldConfig.getPollingIntervalMillis();
        storeNamespace = LDConfig.SHARED_PREFS_BASE_KEY + ldConfig.getMobileKeys().get(environmentName) + "-connectionstatus";
        connectionInformation = new ConnectionInformationState();
        readStoredConnectionState();
        setOffline = ldConfig.isOffline();

        backgroundMode = ldConfig.isDisableBackgroundPolling() ? ConnectionMode.BACKGROUND_DISABLED : ConnectionMode.BACKGROUND_POLLING;
        foregroundMode = ldConfig.isStream() ? ConnectionMode.STREAMING : ConnectionMode.POLLING;

        throttler = new Throttler(() -> {
            synchronized (ConnectivityManager.this) {
                attemptTransition(isForeground() ? foregroundMode : backgroundMode);
            }
        }, RETRY_TIME_MS, MAX_RETRY_TIME_MS);

        foregroundListener = new Foreground.Listener() {
            @Override
            public void onBecameForeground() {
                synchronized (ConnectivityManager.this) {
                    if (isInternetConnected(application) && !setOffline &&
                            connectionInformation.getConnectionMode() != foregroundMode) {
                        throttler.attemptRun();
                        eventProcessor.setInBackground(false);
                    }
                }
            }

            @Override
            public void onBecameBackground() {
                synchronized (ConnectivityManager.this) {
                    if (isInternetConnected(application) && !setOffline &&
                            connectionInformation.getConnectionMode() != backgroundMode) {
                        throttler.cancel();
                        eventProcessor.setInBackground(true);
                        attemptTransition(backgroundMode);
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
                    try {
                        LDClient ldClient = LDClient.getForMobileKey(environmentName);
                        ldClient.updateListenersOnFailure(connectionInformation.getLastFailure());
                    } catch (LaunchDarklyException ex) {
                        LDUtil.logExceptionAtErrorLevel(logger, e, "Error getting LDClient for ConnectivityManager");
                    }
                    callInitCallback();
                }
            }
        };

        streamUpdateProcessor = ldConfig.isStream() ? new StreamUpdateProcessor(ldConfig, contextManager, environmentName,
                diagnosticStore, monitor, logger) : null;
    }

    boolean isInitialized() {
        return initialized;
    }

    private void callInitCallback() {
        voidSuccess(initCallback);
        initCallback = null;
    }

    private void readStoredConnectionState() {
        Long lastSuccess = LDUtil.getStoreValueAsLong(store, storeNamespace, "lastSuccessfulConnection");
        Long lastFailureTime = LDUtil.getStoreValueAsLong(store, storeNamespace, "lastFailedConnection");
        connectionInformation.setLastSuccessfulConnection(lastSuccess == null || lastSuccess.longValue() == 0 ?
                null : lastSuccess.longValue());
        connectionInformation.setLastFailedConnection(lastFailureTime == null || lastFailureTime.longValue() == 0 ?
                null : lastFailureTime.longValue());
        String lastFailureString = store.getValue(storeNamespace, "lastFailure");
        if (lastFailureString != null) {
            try {
                LDFailure lastFailure = gsonInstance().fromJson(lastFailureString, LDFailure.class);
                connectionInformation.setLastFailure(lastFailure);
            } catch (Exception unused) {
                store.setValue(storeNamespace, "lastFailure", null);
                connectionInformation.setLastFailure(null);
            }
        }
    }

    private synchronized void saveConnectionInformation() {
        Long lastSuccessfulConnection = connectionInformation.getLastSuccessfulConnection();
        Long lastFailedConnection = connectionInformation.getLastFailedConnection();
        Map<String, String> updates = new HashMap<>();
        if (lastSuccessfulConnection != null) {
            updates.put("lastSuccessfulConnection", String.valueOf(lastSuccessfulConnection));
        }
        if (lastFailedConnection != null) {
            updates.put("lastFailedConnection", String.valueOf(lastFailedConnection));
        }
        if (connectionInformation.getLastFailure() == null) {
            updates.put("lastFailure", null);
        } else {
            String failJson = gsonInstance().toJson(connectionInformation.getLastFailure());
            updates.put("lastFailure", failJson);
        }
        store.setValues(storeNamespace, updates);
    }

    private void stopPolling() {
        PollingUpdater.stop(application);
    }

    private void startPolling() {
        triggerPoll();
        PollingUpdater.startPolling(application, pollingInterval, pollingInterval);
    }

    private void startBackgroundPolling() {
        if (initCallback != null) {
            initCallback.onSuccess(null);
            initCallback = null;
        }
        PollingUpdater.startBackgroundPolling(application);
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
            voidSuccess(onCompleteListener);
        }
    }

    private void startStreaming() {
        if (streamUpdateProcessor != null) {
            streamUpdateProcessor.start();
        }
    }

    private void removeForegroundListener() {
        Foreground.get(application).removeListener(foregroundListener);
    }

    private void addForegroundListener() {
        Foreground.get(application).removeListener(foregroundListener);
        Foreground.get(application).addListener(foregroundListener);
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

    private boolean isForeground() {
        return Foreground.get(application).isForeground();
    }

    private void voidSuccess(LDUtil.ResultCallback<Void> listener) {
        if (listener != null) {
            listener.onSuccess(null);
        }
    }

    synchronized boolean startUp(LDUtil.ResultCallback<Void> onCompleteListener) {
        initialized = false;
        if (setOffline) {
            initialized = true;
            updateConnectionMode(ConnectionMode.SET_OFFLINE);
            voidSuccess(onCompleteListener);
            return false;
        }

        boolean connected = isInternetConnected(application);

        if (!connected) {
            initialized = true;
            updateConnectionMode(ConnectionMode.OFFLINE);
            voidSuccess(onCompleteListener);
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
        setOffline = true;
        eventProcessor.setOffline(true);
        callInitCallback();
    }

    synchronized void setOnline() {
        if (setOffline) {
            setOffline = false;
            startUp(null);
        }
    }

    synchronized void setOffline() {
        if (!setOffline) {
            setOffline = true;
            throttler.cancel();
            attemptTransition(ConnectionMode.SET_OFFLINE);
            eventProcessor.setOffline(true);
        }
    }

    boolean isOffline() {
        return setOffline;
    }

    synchronized void reloadUser(final LDUtil.ResultCallback<Void> onCompleteListener) {
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
        try {
            LDClient ldClient = LDClient.getForMobileKey(environmentName);
            ldClient.updateListenersConnectionModeChanged(connectionInformation);
        } catch (LaunchDarklyException e) {
            LDUtil.logExceptionAtErrorLevel(logger, e, "Error getting LDClient for ConnectivityManager: {}");
        }
    }

    synchronized void onNetworkConnectivityChange(boolean connectedToInternet) {
        if (setOffline) {
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
        contextManager.updateCurrentContext(monitor);
    }
}
