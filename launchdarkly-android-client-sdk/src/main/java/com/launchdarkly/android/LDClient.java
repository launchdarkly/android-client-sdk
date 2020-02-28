package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import com.google.gson.JsonElement;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import timber.log.Timber;

import static com.launchdarkly.android.TLSUtils.patchTLSIfNeeded;

/**
 * Client for accessing LaunchDarkly's Feature Flag system. This class enforces a singleton pattern.
 * The main entry point is the {@link #init(Application, LDConfig, LDUser)} method.
 */
public class LDClient implements LDClientInterface, Closeable {

    private static final String INSTANCE_ID_KEY = "instanceId";
    // Upon client init will get set to a Unique id per installation used when creating anonymous users
    private static String instanceId = "UNKNOWN_ANDROID";
    private static Map<String, LDClient> instances = null;

    private final Application application;
    private final LDConfig config;
    private final DefaultUserManager userManager;
    private final DefaultEventProcessor eventProcessor;
    private final ConnectivityManager connectivityManager;
    private final DiagnosticEventProcessor diagnosticEventProcessor;
    private final DiagnosticStore diagnosticStore;
    private ConnectivityReceiver connectivityReceiver;
    private final List<WeakReference<LDStatusListener>> connectionFailureListeners =
            Collections.synchronizedList(new ArrayList<WeakReference<LDStatusListener>>());

    /**
     * Initializes the singleton/primary instance. The result is a {@link Future} which
     * will complete once the client has been initialized with the latest feature flag values. For
     * immediate access to the Client (possibly with out of date feature flags), it is safe to ignore
     * the return value of this method, and afterward call {@link #get()}
     * <p>
     * If the client has already been initialized, is configured for offline mode, or the device is
     * not connected to the internet, this method will return a {@link Future} that is
     * already in the completed state.
     *
     * @param application Your Android application.
     * @param config      Configuration used to set up the client
     * @param user        The user used in evaluating feature flags
     * @return a {@link Future} which will complete once the client has been initialized.
     */
    public static synchronized Future<LDClient> init(@NonNull Application application,
                                                     @NonNull LDConfig config,
                                                     @NonNull LDUser user) {
        // As this is an externally facing API we should still check these, so we hide the linter
        // warnings

        //noinspection ConstantConditions
        if (application == null) {
            return new LDFailedFuture<>(new LaunchDarklyException("Client initialization requires a valid application"));
        }
        //noinspection ConstantConditions
        if (config == null) {
            return new LDFailedFuture<>(new LaunchDarklyException("Client initialization requires a valid configuration"));
        }
        //noinspection ConstantConditions
        if (user == null) {
            return new LDFailedFuture<>(new LaunchDarklyException("Client initialization requires a valid user"));
        }

        if (instances != null) {
            Timber.w("LDClient.init() was called more than once! returning primary instance.");
            return new LDSuccessFuture<>(instances.get(LDConfig.primaryEnvironmentName));
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }

        patchTLSIfNeeded(application);
        Foreground.init(application);

        instances = new HashMap<>();

        SharedPreferences instanceIdSharedPrefs =
                application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "id", Context.MODE_PRIVATE);

        if (!instanceIdSharedPrefs.contains(INSTANCE_ID_KEY)) {
            String uuid = UUID.randomUUID().toString();
            Timber.i("Did not find existing instance id. Saving a new one");
            SharedPreferences.Editor editor = instanceIdSharedPrefs.edit();
            editor.putString(INSTANCE_ID_KEY, uuid);
            editor.apply();
        }

        instanceId = instanceIdSharedPrefs.getString(INSTANCE_ID_KEY, instanceId);
        Timber.i("Using instance id: %s", instanceId);

        Migration.migrateWhenNeeded(application, config);

        final LDAwaitFuture<LDClient> resultFuture = new LDAwaitFuture<>();
        final AtomicInteger initCounter = new AtomicInteger(config.getMobileKeys().size());
        LDUtil.ResultCallback<Void> completeWhenCounterZero = new LDUtil.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (initCounter.decrementAndGet() == 0) {
                    resultFuture.set(instances.get(LDConfig.primaryEnvironmentName));
                }
            }

            @Override
            public void onError(Throwable e) {
                resultFuture.setException(e);
            }
        };

        PollingUpdater.setBackgroundPollingIntervalMillis(config.getBackgroundPollingIntervalMillis());

        for (Map.Entry<String, String> mobileKeys : config.getMobileKeys().entrySet()) {
            final LDClient instance = new LDClient(application, config, mobileKeys.getKey());
            instance.userManager.setCurrentUser(user);

            instances.put(mobileKeys.getKey(), instance);
            if (instance.connectivityManager.startUp(completeWhenCounterZero)) {
                instance.sendEvent(new IdentifyEvent(user));
            }
        }

        return resultFuture;
    }

    /**
     * Initializes the singleton instance and blocks for up to <code>startWaitSeconds</code> seconds
     * until the client has been initialized. If the client does not initialize within
     * <code>startWaitSeconds</code> seconds, it is returned anyway and can be used, but may not
     * have fetched the most recent feature flag values.
     *
     * @param application      Your Android application.
     * @param config           Configuration used to set up the client
     * @param user             The user used in evaluating feature flags
     * @param startWaitSeconds Maximum number of seconds to wait for the client to initialize
     * @return The primary LDClient instance
     */
    public static synchronized LDClient init(Application application, LDConfig config, LDUser user, int startWaitSeconds) {
        Timber.i("Initializing Client and waiting up to %s for initialization to complete", startWaitSeconds);
        Future<LDClient> initFuture = init(application, config, user);
        try {
            return initFuture.get(startWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            Timber.e(e, "Exception during Client initialization");
        } catch (TimeoutException e) {
            Timber.w("Client did not successfully initialize within %s seconds. It could be taking longer than expected to start up", startWaitSeconds);
        }
        return instances.get(LDConfig.primaryEnvironmentName);
    }

    /**
     * @return the singleton instance.
     * @throws LaunchDarklyException if {@link #init(Application, LDConfig, LDUser)} has not been called.
     */
    public static LDClient get() throws LaunchDarklyException {
        if (instances == null) {
            Timber.e("LDClient.get() was called before init()!");
            throw new LaunchDarklyException("LDClient.get() was called before init()!");
        }
        return instances.get(LDConfig.primaryEnvironmentName);
    }

    /**
     * @return the singleton instance for the environment associated with the given name.
     * @param keyName The name to lookup the instance by.
     * @throws LaunchDarklyException if {@link #init(Application, LDConfig, LDUser)} has not been called.
     */
    @SuppressWarnings("WeakerAccess")
    public static LDClient getForMobileKey(String keyName) throws LaunchDarklyException {
        if (instances == null) {
            Timber.e("LDClient.getForMobileKey() was called before init()!");
            throw new LaunchDarklyException("LDClient.getForMobileKey() was called before init()!");
        }
        if (!(instances.containsKey(keyName))) {
            throw new LaunchDarklyException("LDClient.getForMobileKey() called with invalid keyName");
        }
        return instances.get(keyName);
    }

    @VisibleForTesting
    protected LDClient(final Application application, @NonNull final LDConfig config) {
        this(application, config, LDConfig.primaryEnvironmentName);
    }

    @VisibleForTesting
    protected LDClient(final Application application, @NonNull final LDConfig config, final String environmentName) {
        Timber.i("Creating LaunchDarkly client. Version: %s", BuildConfig.VERSION_NAME);
        this.config = config;
        this.application = application;
        String sdkKey = config.getMobileKeys().get(environmentName);
        FeatureFetcher fetcher = HttpFeatureFlagFetcher.newInstance(application, config, environmentName);
        OkHttpClient sharedEventClient = makeSharedEventClient();
        if (config.getDiagnosticOptOut()) {
            this.diagnosticStore = null;
            this.diagnosticEventProcessor = null;
        } else {
            this.diagnosticStore = new DiagnosticStore(application, sdkKey);
            this.diagnosticEventProcessor = new DiagnosticEventProcessor(config, environmentName, diagnosticStore, sharedEventClient);
        }
        this.userManager = DefaultUserManager.newInstance(application, fetcher, environmentName, sdkKey);

        eventProcessor = new DefaultEventProcessor(application, config, userManager.getSummaryEventStore(), environmentName, diagnosticStore, sharedEventClient);
        connectivityManager = new ConnectivityManager(application, config, eventProcessor, userManager, environmentName, diagnosticStore);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityReceiver = new ConnectivityReceiver();
            IntentFilter filter = new IntentFilter(ConnectivityReceiver.CONNECTIVITY_CHANGE);
            application.registerReceiver(connectivityReceiver, filter);
        }
    }

    private OkHttpClient makeSharedEventClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(1, config.getEventsFlushIntervalMillis() * 2, TimeUnit.MILLISECONDS))
                .connectTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(new SSLHandshakeInterceptor());

        if (Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 22) {
            try {
                builder.sslSocketFactory(new ModernTLSSocketFactory(), TLSUtils.defaultTrustManager());
            } catch (GeneralSecurityException ignored) {
                // TLS is not available, so don't set up the socket factory, swallow the exception
            }
        }

        return builder.build();
    }

    @Override
    public void track(String eventName, JsonElement data, Double metricValue) {
        if (config.inlineUsersInEvents()) {
            sendEvent(new CustomEvent(eventName, userManager.getCurrentUser(), data, metricValue));
        } else {
            sendEvent(new CustomEvent(eventName, userManager.getCurrentUser().getKey(), data, metricValue));
        }
    }

    @Override
    public void track(String eventName, JsonElement data) {
        track(eventName, data, null);
    }

    @Override
    public void track(String eventName) {
        track(eventName, null);
    }

    @Override
    public Future<Void> identify(LDUser user) {
        if (user == null) {
            return new LDFailedFuture<>(new LaunchDarklyException("User cannot be null"));
        }
        if (user.getKey() == null) {
            Timber.w("identify called with null user or null user key!");
        }
        return LDClient.identifyInstances(user);
    }

    private synchronized void identifyInternal(@NonNull LDUser user,
                                               LDUtil.ResultCallback<Void> onCompleteListener) {
        userManager.setCurrentUser(user);
        connectivityManager.reloadUser(onCompleteListener);
        sendEvent(new IdentifyEvent(user));
    }

    private static synchronized Future<Void> identifyInstances(@NonNull LDUser user) {
        final LDAwaitFuture<Void> resultFuture = new LDAwaitFuture<>();
        final AtomicInteger identifyCounter = new AtomicInteger(instances.size());
        LDUtil.ResultCallback<Void> completeWhenCounterZero = new LDUtil.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (identifyCounter.decrementAndGet() == 0) {
                    resultFuture.set(null);
                }
            }

            @Override
            public void onError(Throwable e) {
                resultFuture.setException(e);
            }
        };

        for (LDClient client : instances.values()) {
            client.identifyInternal(user, completeWhenCounterZero);
        }

        return resultFuture;
    }

    @Override
    public Map<String, ?> allFlags() {
        Map<String, Object> result = new HashMap<>();
        for (Flag flag : userManager.getCurrentUserFlagStore().getAllFlags()) {
            JsonElement jsonVal = flag.getValue();
            if (jsonVal == null || jsonVal.isJsonNull()) {
                // TODO(gwhelanld): Include null flag values in results in 3.0.0
                continue;
            } else if (jsonVal.isJsonPrimitive() && jsonVal.getAsJsonPrimitive().isBoolean()) {
                result.put(flag.getKey(), jsonVal.getAsBoolean());
            } else if (jsonVal.isJsonPrimitive() && jsonVal.getAsJsonPrimitive().isNumber()) {
                result.put(flag.getKey(), jsonVal.getAsFloat());
            } else if (jsonVal.isJsonPrimitive() && jsonVal.getAsJsonPrimitive().isString()) {
                result.put(flag.getKey(), jsonVal.getAsString());
            } else {
                // Returning JSON flag as String for backwards compatibility. In the next major
                // release (3.0.0) this method will return a Map containing JsonElements for JSON
                // flags
                result.put(flag.getKey(), GsonCache.getGson().toJson(jsonVal));
            }
        }
        return result;
    }

    @Override
    public Boolean boolVariation(String flagKey, Boolean fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.BOOLEAN, false).getValue();
    }

    @Override
    public EvaluationDetail<Boolean> boolVariationDetail(String flagKey, Boolean fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.BOOLEAN, true);
    }

    @Override
    public Integer intVariation(String flagKey, Integer fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.INT, false).getValue();
    }

    @Override
    public EvaluationDetail<Integer> intVariationDetail(String flagKey, Integer fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.INT, true);
    }

    @Override
    public Float floatVariation(String flagKey, Float fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.FLOAT, false).getValue();
    }

    @Override
    public EvaluationDetail<Float> floatVariationDetail(String flagKey, Float fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.FLOAT, true);
    }

    @Override
    public String stringVariation(String flagKey, String fallback) {
        // TODO(gwhelanld): Change to ValueTypes.String in 3.0.0
        return variationDetailInternal(flagKey, fallback, ValueTypes.STRINGCOMPAT, false).getValue();
    }

    @Override
    public EvaluationDetail<String> stringVariationDetail(String flagKey, String fallback) {
        // TODO(gwhelanld): Change to ValueTypes.String in 3.0.0
        return variationDetailInternal(flagKey, fallback, ValueTypes.STRINGCOMPAT, true);
    }

    @Override
    public JsonElement jsonVariation(String flagKey, JsonElement fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.JSON, false).getValue();
    }

    @Override
    public EvaluationDetail<JsonElement> jsonVariationDetail(String flagKey, JsonElement fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.JSON, true);
    }

    private <T> EvaluationDetail<T> variationDetailInternal(String flagKey, T fallback, ValueTypes.Converter<T> typeConverter, boolean includeReasonInEvent) {
        if (flagKey == null) {
            Timber.e("Attempted to get flag with a null value for key. Returning fallback: %s", fallback);
            return EvaluationDetail.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND, fallback);  // no event is sent in this case
        }

        Flag flag = userManager.getCurrentUserFlagStore().getFlag(flagKey);
        JsonElement fallbackJson = fallback == null ? null : typeConverter.valueToJson(fallback);
        JsonElement valueJson = fallbackJson;
        EvaluationDetail<T> result;

        if (flag == null) {
            Timber.e("Attempted to get non-existent flag for key: %s Returning fallback: %s", flagKey, fallback);
            result = EvaluationDetail.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND, fallback);
        } else {
            valueJson = flag.getValue();
            if (valueJson == null || valueJson.isJsonNull()) {
                Timber.e("Attempted to get flag without value for key: %s Returning fallback: %s", flagKey, fallback);
                result = new EvaluationDetail<>(flag.getReason(), flag.getVariation(), fallback);
                valueJson = fallbackJson;
            } else {
                T value = typeConverter.valueFromJson(valueJson);
                if (value == null) {
                    Timber.e("Attempted to get flag with wrong type for key: %s Returning fallback: %s", flagKey, fallback);
                    result = EvaluationDetail.error(EvaluationReason.ErrorKind.WRONG_TYPE, fallback);
                    valueJson = fallbackJson;
                } else {
                    result = new EvaluationDetail<>(flag.getReason(), flag.getVariation(), value);
                }
            }
            sendFlagRequestEvent(flagKey, flag, valueJson, fallbackJson, flag.isTrackReason() | includeReasonInEvent ? result.getReason() : null);
        }

        updateSummaryEvents(flagKey, flag, valueJson, fallbackJson);
        Timber.d("returning variation: %s flagKey: %s user key: %s", result, flagKey, userManager.getCurrentUser().getKey());
        return result;
    }

    /**
     * Closes the client. This should only be called at the end of a client's lifecycle.
     *
     * @throws IOException declared by the Closeable interface, but will not be thrown by the client
     */
    @Override
    public void close() throws IOException {
        LDClient.closeInstances();
    }

    private void closeInternal() {
        connectivityManager.shutdown();
        eventProcessor.close();
        if (diagnosticEventProcessor != null) {
            diagnosticEventProcessor.close();
        }
        if (connectivityReceiver != null && application != null) {
            application.unregisterReceiver(connectivityReceiver);
            connectivityReceiver = null;
        }
    }

    private static void closeInstances() {
        for (LDClient client : instances.values()) {
            client.closeInternal();
        }
    }

    @Override
    public void flush() {
        LDClient.flushInstances();
    }

    private void flushInternal() {
        eventProcessor.flush();
    }

    private static void flushInstances() {
        for (LDClient client : instances.values()) {
            client.flushInternal();
        }
    }

    @VisibleForTesting
    void blockingFlush() {
        eventProcessor.blockingFlush();
    }

    @Override
    public boolean isInitialized() {
        return connectivityManager.isOffline() || connectivityManager.isInitialized();
    }

    @Override
    public boolean isOffline() {
        return connectivityManager.isOffline();
    }

    @Override
    public synchronized void setOffline() {
        LDClient.setInstancesOffline();
    }

    private synchronized void setOfflineInternal() {
        connectivityManager.setOffline();
    }

    private synchronized static void setInstancesOffline() {
        for (LDClient client : instances.values()) {
            client.setOfflineInternal();
        }
    }

    @Override
    public synchronized void setOnline() {
        setOnlineStatusInstances();
    }

    private void setOnlineStatusInternal() {
        connectivityManager.setOnline();
    }

    private static void setOnlineStatusInstances() {
        for (LDClient client : instances.values()) {
            client.setOnlineStatusInternal();
        }
    }

    @Override
    public void registerFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener) {
        userManager.registerListener(flagKey, listener);
    }

    @Override
    public void unregisterFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener) {
        userManager.unregisterListener(flagKey, listener);
    }

    @Override
    public boolean isDisableBackgroundPolling() {
        return config.isDisableBackgroundPolling();
    }

    public ConnectionInformation getConnectionInformation() {
        return connectivityManager.getConnectionInformation();
    }

    public void registerStatusListener(LDStatusListener LDStatusListener) {
        if (LDStatusListener == null) {
            return;
        }
        synchronized (connectionFailureListeners) {
            connectionFailureListeners.add(new WeakReference<>(LDStatusListener));
        }
    }

    public void unregisterStatusListener(LDStatusListener LDStatusListener) {
        if (LDStatusListener == null) {
            return;
        }
        synchronized (connectionFailureListeners) {
            Iterator<WeakReference<LDStatusListener>> iter = connectionFailureListeners.iterator();
            while (iter.hasNext()) {
                LDStatusListener mListener = iter.next().get();
                if (mListener == null || mListener == LDStatusListener) {
                    iter.remove();
                }
            }
        }
    }

    public void registerAllFlagsListener(LDAllFlagsListener allFlagsListener) {
        userManager.registerAllFlagsListener(allFlagsListener);
    }

    public void unregisterAllFlagsListener(LDAllFlagsListener allFlagsListener) {
        userManager.unregisterAllFlagsListener(allFlagsListener);
    }

    private void triggerPoll() {
        connectivityManager.triggerPoll();
    }

    void updateListenersConnectionModeChanged(final ConnectionInformation connectionInformation) {
        synchronized (connectionFailureListeners) {
            Iterator<WeakReference<LDStatusListener>> iter = connectionFailureListeners.iterator();
            while (iter.hasNext()) {
                final LDStatusListener mListener = iter.next().get();
                if (mListener == null) {
                    iter.remove();
                } else {
                    AsyncTask.execute(new Runnable() {
                        @Override
                        public void run() {
                            mListener.onConnectionModeChanged(connectionInformation);
                        }
                    });
                }
            }
        }
    }

    void updateListenersOnFailure(final LDFailure ldFailure) {
        synchronized (connectionFailureListeners) {
            Iterator<WeakReference<LDStatusListener>> iter = connectionFailureListeners.iterator();
            while (iter.hasNext()) {
                final LDStatusListener mListener = iter.next().get();
                if (mListener == null) {
                    iter.remove();
                } else {
                    AsyncTask.execute(new Runnable() {
                        @Override
                        public void run() {
                            mListener.onInternalFailure(ldFailure);
                        }
                    });
                }
            }
        }
    }

    @Override
    public String getVersion() {
        return BuildConfig.VERSION_NAME;
    }

    static String getInstanceId() {
        return instanceId;
    }

    private void onNetworkConnectivityChange(boolean connectedToInternet) {
        connectivityManager.onNetworkConnectivityChange(connectedToInternet);
    }

    private void sendFlagRequestEvent(String flagKey, Flag flag, JsonElement value, JsonElement fallback, EvaluationReason reason) {
        int version = flag.getVersionForEvents();
        Integer variation = flag.getVariation();
        if (flag.getTrackEvents()) {
            if (config.inlineUsersInEvents()) {
                sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser(), value, fallback, version, variation, reason));
            } else {
                sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser().getKey(), value, fallback, version, variation, reason));
            }
        } else {
            Long debugEventsUntilDate = flag.getDebugEventsUntilDate();
            if (debugEventsUntilDate != null) {
                long serverTimeMs = eventProcessor.getCurrentTimeMs();
                if (debugEventsUntilDate > System.currentTimeMillis() && debugEventsUntilDate > serverTimeMs) {
                    sendEvent(new DebugEvent(flagKey, userManager.getCurrentUser(), value, fallback, version, variation, reason));
                }
            }
        }
    }

    private void sendEvent(Event event) {
        if (!connectivityManager.isOffline()) {
            boolean processed = eventProcessor.sendEvent(event);
            if (!processed) {
                Timber.w("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
                diagnosticStore.incrementDroppedEventCount();
            }
        }
    }

    /**
     * Updates the internal representation of a summary event, either adding a new field or updating the existing count.
     * Nothing is sent to the server.
     *
     * @param flagKey  The flagKey that will be updated
     * @param flag     The stored flag used in the evaluation of the flagKey
     * @param result   The value that was returned in the evaluation of the flagKey
     * @param fallback The fallback value used in the evaluation of the flagKey
     */
    private void updateSummaryEvents(String flagKey, Flag flag, JsonElement result, JsonElement fallback) {
        if (flag == null) {
            userManager.getSummaryEventStore().addOrUpdateEvent(flagKey, result, fallback, -1, null);
        } else {
            int version = flag.getVersionForEvents();
            Integer variation = flag.getVariation();
            userManager.getSummaryEventStore().addOrUpdateEvent(flagKey, result, fallback, version, variation);
        }
    }

    static synchronized void triggerPollInstances() {
        if (instances == null) {
            Timber.w("Cannot perform poll when LDClient has not been initialized!");
            return;
        }
        for (LDClient instance : instances.values()) {
            instance.triggerPoll();
        }
    }

    static synchronized void onNetworkConnectivityChangeInstances(boolean network) {
        if (instances == null) {
            Timber.e("Tried to update LDClients with network connectivity status, but LDClient has not yet been initialized.");
            return;
        }
        for (LDClient instance : instances.values()) {
            instance.onNetworkConnectivityChange(network);
        }
    }

    @VisibleForTesting
    SummaryEventStore getSummaryEventStore() {
        return userManager.getSummaryEventStore();
    }
}
