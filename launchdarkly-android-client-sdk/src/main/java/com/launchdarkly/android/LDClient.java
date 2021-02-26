package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.google.gson.*;
import com.launchdarkly.sdk.*;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

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
            // TODO: remove when done debugging
            for (StackTraceElement elem : Thread.currentThread().getStackTrace()) {
                LDConfig.LOG.w(elem.getFileName() + " " + elem.getMethodName() + ":" + elem.getLineNumber());
            }

            LDConfig.LOG.w("LDClient.init() was called more than once! returning primary instance.");
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
            LDConfig.LOG.i("Did not find existing instance id. Saving a new one");
            SharedPreferences.Editor editor = instanceIdSharedPrefs.edit();
            editor.putString(INSTANCE_ID_KEY, uuid);
            editor.apply();
        }

        instanceId = instanceIdSharedPrefs.getString(INSTANCE_ID_KEY, instanceId);
        LDConfig.LOG.i("Using instance id: %s", instanceId);

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
        LDConfig.LOG.i("Initializing Client and waiting up to %s for initialization to complete", startWaitSeconds);
        Future<LDClient> initFuture = init(application, config, user);
        try {
            return initFuture.get(startWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            LDConfig.LOG.e(e, "Exception during Client initialization");
        } catch (TimeoutException e) {
            LDConfig.LOG.w("Client did not successfully initialize within %s seconds. It could be taking longer than expected to start up", startWaitSeconds);
        }
        return instances.get(LDConfig.primaryEnvironmentName);
    }

    /**
     * @return the singleton instance.
     * @throws LaunchDarklyException if {@link #init(Application, LDConfig, LDUser)} has not been called.
     */
    public static LDClient get() throws LaunchDarklyException {
        if (instances == null) {
            LDConfig.LOG.e("LDClient.get() was called before init()!");
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
            LDConfig.LOG.e("LDClient.getForMobileKey() was called before init()!");
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
        LDConfig.LOG.i("Creating LaunchDarkly client. Version: %s", BuildConfig.VERSION_NAME);
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
        this.userManager = DefaultUserManager.newInstance(application, fetcher, environmentName, sdkKey, config.getMaxCachedUsers());

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

        LDUtil.setupSocketFactory(builder);

        return builder.build();
    }

    @Override
    public void track(String eventName, LDValue data, Double metricValue) {
        trackMetric(eventName, data, metricValue);
    }
    
    @Override
    public void track(String eventName, LDValue data) {
        track(eventName, data, null);
    }

    @Override
    public void track(String eventName) {
        track(eventName, null, null);
    }

    public void trackMetric(String eventName, LDValue data, Double metricValue) {
        sendEvent(new CustomEvent(eventName, userManager.getCurrentUser(), data, metricValue, config.inlineUsersInEvents()));
    }

    @Override
    public Future<Void> identify(LDUser user) {
        if (user == null) {
            return new LDFailedFuture<>(new LaunchDarklyException("User cannot be null"));
        }
        if (user.getKey() == null) {
            LDConfig.LOG.w("identify called with null user or null user key!");
        }
        return LDClient.identifyInstances(user);
    }

    private synchronized void identifyInternal(@NonNull LDUser user,
                                               LDUtil.ResultCallback<Void> onCompleteListener) {
        if (!config.isAutoAliasingOptOut()) {
            LDUser previousUser = userManager.getCurrentUser();
            if (Event.userContextKind(previousUser).equals("anonymousUser") && Event.userContextKind(user).equals("user")) {
                sendEvent(new AliasEvent(user, previousUser));
            }
        }
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
            LDValue value = flag.getValue();
            switch (value.getType()) {
            case BOOLEAN:
                result.put(flag.getKey(), value.booleanValue());
                break;
            case NUMBER:
                result.put(flag.getKey(), value.floatValue());
                break;
            case STRING:
                result.put(flag.getKey(), value.stringValue());
                break;
            case NULL:
                // TODO(gwhelanld): Include null flag values in results in 3.0.0
                continue;
            default:
                result.put(flag.getKey(), value.toJsonString());
                break;
            }
        }
        return result;
    }

    @Override
    public boolean boolVariation(@NonNull String key, boolean fallback) {
        return variationDetailInternal(key, fallback, LDValue.Convert.Boolean, LDValueType.BOOLEAN, false).getValue();
    }

    @Override 
    public EvaluationDetail<Boolean> boolVariationDetail(@NonNull String key, boolean fallback) {
        return variationDetailInternal(key, fallback, LDValue.Convert.Boolean, LDValueType.BOOLEAN, true);
    }

    @Override
    public int intVariation(@NonNull String key, int fallback) {
        return variationDetailInternal(key, fallback, LDValue.Convert.Integer, LDValueType.NUMBER, false).getValue();
    }

    @Override
    public EvaluationDetail<Integer> intVariationDetail(@NonNull String key, int fallback) {
        return variationDetailInternal(key, fallback, LDValue.Convert.Integer, LDValueType.NUMBER, true);
    }

    @Override
    public double doubleVariation(String flagKey, double fallback) {
        return variationDetailInternal(flagKey, fallback, LDValue.Convert.Double, LDValueType.NUMBER, false).getValue();
    }

    @Override
    public EvaluationDetail<Double> doubleVariationDetail(String flagKey, double fallback) {
        return variationDetailInternal(flagKey, fallback, LDValue.Convert.Double, LDValueType.NUMBER, true);
    }

    @Override
    public String stringVariation(@NonNull String key, String fallback) {
        return variationDetailInternal(key, fallback, LDValue.Convert.String, LDValueType.STRING, false).getValue();
    }

    @Override
    public EvaluationDetail<String> stringVariationDetail(@NonNull String key, String fallback) {
        return variationDetailInternal(key, fallback, LDValue.Convert.String, LDValueType.STRING, true);
    }

    private static LDValue.Converter<LDValue> value = new LDValue.Converter<LDValue>() {
        public LDValue fromType(LDValue value) { return value; }
        public LDValue toType(LDValue value) { return value; }
    };

    @Override
    public LDValue jsonValueVariation(@NonNull String key, LDValue fallback) {
        return variationDetailInternal(key, fallback, value, LDValueType.OBJECT, false).getValue();
    }

    @Override
    public EvaluationDetail<LDValue> jsonValueVariationDetail(@NonNull String key, LDValue fallback) {
        return variationDetailInternal(key, fallback, value, LDValueType.OBJECT, true);
    }


    private <T> EvaluationDetail<T> variationDetailInternal(@NonNull String key, T fallback, LDValue.Converter<T> converter, LDValueType type, boolean needsReason) {
        Flag flag = userManager.getCurrentUserFlagStore().getFlag(key);
        EvaluationDetail<T> result;
        LDValue value = converter.fromType(fallback);

        if (flag == null) {
            LDConfig.LOG.e("Attempted to get non-existent flag for key: %s Returning fallback: %s", key, fallback);
            result = EvaluationDetail.fromValue(fallback, 0, EvaluationReason.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND));
        } else {
            value = flag.getValue();
            if (value.isNull()) {
                LDConfig.LOG.e("Attempted to get flag without value for key: %s Returning fallback: %s", key, fallback);
                value = converter.fromType(fallback);
                int variation = flag.getVariation() == null ? EvaluationDetail.NO_VARIATION : flag.getVariation();
                result = EvaluationDetail.fromValue(fallback, variation, EvaluationReason.off());
            } else {
                if (value.getType() != type) {
                    LDConfig.LOG.e("Attempted to get flag with wrong type for key: %s Returning fallback: %s", key, fallback);
                    value = converter.fromType(fallback);
                    result = EvaluationDetail.fromValue(fallback, flag.getVariation(), EvaluationReason.error(EvaluationReason.ErrorKind.MALFORMED_FLAG));
                } else {
                    result = EvaluationDetail.fromValue(converter.toType(value), flag.getVariation(), flag.getReason());
                }
            }
            sendFlagRequestEvent(key, flag, value, converter.fromType(fallback), flag.isTrackReason() | needsReason ? result.getReason() : null);
        }

        LDConfig.LOG.d("returning variation: %s flagKey: %s user key: %s", result, key, userManager.getCurrentUser().getKey());
        updateSummaryEvents(key, flag, value, converter.fromType(fallback));
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
        
        if (connectivityReceiver != null) {
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

    /**
     * Alias associates two users for analytics purposes.
     *
     * @param user The first user
     * @param previousUser The second user
     */
    public void alias(LDUser user, LDUser previousUser) {
        sendEvent(new AliasEvent(user, previousUser));
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
                    executor.submit(() -> mListener.onConnectionModeChanged(connectionInformation));
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
                    executor.submit(() -> mListener.onInternalFailure(ldFailure));
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

    private void sendFlagRequestEvent(String flagKey, Flag flag, LDValue value, LDValue fallback, EvaluationReason reason) {
        int version = flag.getVersionForEvents();
        Integer variation = flag.getVariation();
        if (flag.getTrackEvents()) {
            sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser(), value, fallback, version,
                    variation, reason, config.inlineUsersInEvents(), false));
        } else {
            Long debugEventsUntilDate = flag.getDebugEventsUntilDate();
            if (debugEventsUntilDate != null) {
                long serverTimeMs = eventProcessor.getCurrentTimeMs();
                if (debugEventsUntilDate > System.currentTimeMillis() && debugEventsUntilDate > serverTimeMs) {
                    sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser(), value, fallback, version,
                            variation, reason, false, true));
                }
            }
        }
    }

    private void sendEvent(Event event) {
        if (!connectivityManager.isOffline()) {
            boolean processed = eventProcessor.sendEvent(event);
            if (!processed) {
                LDConfig.LOG.w("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
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
    @SuppressWarnings("deprecation")
    private void updateSummaryEvents(String flagKey, Flag flag, LDValue result, LDValue fallback) {
        if (flag == null) {
            userManager.getSummaryEventStore().addOrUpdateEvent(flagKey, LDValue.normalize(result), LDValue.normalize(fallback), -1, null);
        } else {
            int version = flag.getVersionForEvents();
            Integer variation = flag.getVariation();
            userManager.getSummaryEventStore().addOrUpdateEvent(flagKey, LDValue.normalize(result), LDValue.normalize(fallback), version, variation);
        }
    }

    static synchronized void triggerPollInstances() {
        if (instances == null) {
            LDConfig.LOG.w("Cannot perform poll when LDClient has not been initialized!");
            return;
        }
        for (LDClient instance : instances.values()) {
            instance.triggerPoll();
        }
    }

    static synchronized void onNetworkConnectivityChangeInstances(boolean network) {
        if (instances == null) {
            LDConfig.LOG.e("Tried to update LDClients with network connectivity status, but LDClient has not yet been initialized.");
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
