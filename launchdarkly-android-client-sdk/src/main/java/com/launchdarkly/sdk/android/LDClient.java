package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.UserAttribute;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

/**
 * Client for accessing LaunchDarkly's Feature Flag system. This class enforces a singleton pattern.
 * The main entry point is the {@link #init(Application, LDConfig, LDUser)} method.
 */
public class LDClient implements LDClientInterface, Closeable {

    private static final String INSTANCE_ID_KEY = "instanceId";
    // Upon client init will get set to a Unique id per installation used when creating anonymous users
    private static String instanceId = "UNKNOWN_ANDROID";
    // A map of each LDClient (one per environment), or null if `init` hasn't been called yet.
    // Will only be set once, during initialization, and the map is considered immutable.
    static volatile Map<String, LDClient> instances = null;
    // A lock to ensure calls to `init()` are serialized.
    static Object initLock = new Object();

    private static volatile LDLogger sharedLogger;

    private final Application application;
    private final LDConfig config;
    private final DefaultUserManager userManager;
    private final DefaultEventProcessor eventProcessor;
    private final ConnectivityManager connectivityManager;
    private final DiagnosticEventProcessor diagnosticEventProcessor;
    private final DiagnosticStore diagnosticStore;
    private ConnectivityReceiver connectivityReceiver;
    private final List<WeakReference<LDStatusListener>> connectionFailureListeners =
            Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private final LDLogger logger;

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
    public static Future<LDClient> init(@NonNull Application application,
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

        initSharedLogger(config);

        // Acquire the `initLock` to ensure that if `init()` is called multiple times, we will only
        // initialize the client(s) once.
        synchronized (initLock) {
            if (instances != null) {
                getSharedLogger().warn("LDClient.init() was called more than once! returning primary instance.");
                return new LDSuccessFuture<>(instances.get(LDConfig.primaryEnvironmentName));
            }

            Foreground.init(application);

            SharedPreferences instanceIdSharedPrefs =
                    application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "id", Context.MODE_PRIVATE);

            if (!instanceIdSharedPrefs.contains(INSTANCE_ID_KEY)) {
                String uuid = UUID.randomUUID().toString();
                getSharedLogger().info("Did not find existing instance id. Saving a new one");
                SharedPreferences.Editor editor = instanceIdSharedPrefs.edit();
                editor.putString(INSTANCE_ID_KEY, uuid);
                editor.apply();
            }

            instanceId = instanceIdSharedPrefs.getString(INSTANCE_ID_KEY, instanceId);
            getSharedLogger().info("Using instance id: {}", instanceId);

            Migration.migrateWhenNeeded(application, config);

            // Create, but don't start, every LDClient instance
            final Map<String, LDClient> newInstances = new HashMap<>();

            for (Map.Entry<String, String> mobileKeys : config.getMobileKeys().entrySet()) {
                final LDClient instance = new LDClient(application, config, mobileKeys.getKey());
                instance.userManager.setCurrentUser(user);

                newInstances.put(mobileKeys.getKey(), instance);
            }

            instances = newInstances;

            final LDAwaitFuture<LDClient> resultFuture = new LDAwaitFuture<>();
            final AtomicInteger initCounter = new AtomicInteger(config.getMobileKeys().size());
            LDUtil.ResultCallback<Void> completeWhenCounterZero = new LDUtil.ResultCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    if (initCounter.decrementAndGet() == 0) {
                        resultFuture.set(newInstances.get(LDConfig.primaryEnvironmentName));
                    }
                }

                @Override
                public void onError(Throwable e) {
                    resultFuture.setException(e);
                }
            };

            PollingUpdater.setBackgroundPollingIntervalMillis(config.getBackgroundPollingIntervalMillis());

            user = customizeUser(user);

            // Start up all instances
            for (final LDClient instance : instances.values()) {
                if (instance.connectivityManager.startUp(completeWhenCounterZero)) {
                    instance.sendEvent(new IdentifyEvent(user));
                }
            }

            return resultFuture;
        }
    }

    @VisibleForTesting
    static LDUser customizeUser(LDUser user) {
        LDUser.Builder builder = new LDUser.Builder(user);

        if (user.getAttribute(UserAttribute.forName("os")).isNull()) {
            builder.custom("os", Build.VERSION.SDK_INT);
        }
        if (user.getAttribute(UserAttribute.forName("device")).isNull()) {
            builder.custom("device", Build.MODEL + " " + Build.PRODUCT);
        }

        String key = user.getKey();
        if (key == null || key.equals("")) {
            getSharedLogger().info("User was created with null/empty key. Using device-unique anonymous user key: {}", LDClient.getInstanceId());
            builder.key(LDClient.getInstanceId());
            builder.anonymous(true);
        }

        return builder.build();
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
    public static LDClient init(Application application, LDConfig config, LDUser user, int startWaitSeconds) {
        initSharedLogger(config);
        getSharedLogger().info("Initializing Client and waiting up to {} for initialization to complete", startWaitSeconds);
        Future<LDClient> initFuture = init(application, config, user);
        try {
            return initFuture.get(startWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            getSharedLogger().error("Exception during Client initialization: {}", LogValues.exceptionSummary(e));
            getSharedLogger().debug(LogValues.exceptionTrace(e));
        } catch (TimeoutException e) {
            getSharedLogger().warn("Client did not successfully initialize within {} seconds. It could be taking longer than expected to start up", startWaitSeconds);
        }
        return instances.get(LDConfig.primaryEnvironmentName);
    }

    /**
     * @return the singleton instance.
     * @throws LaunchDarklyException if {@link #init(Application, LDConfig, LDUser)} has not been called.
     */
    public static LDClient get() throws LaunchDarklyException {
        if (instances == null) {
            getSharedLogger().error("LDClient.get() was called before init()!");
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
        Map<String, LDClient> instancesNow = instances; // ensures atomicity
        if (instancesNow == null) {
            getSharedLogger().error("LDClient.getForMobileKey() was called before init()!");
            throw new LaunchDarklyException("LDClient.getForMobileKey() was called before init()!");
        }
        if (!(instancesNow.containsKey(keyName))) {
            throw new LaunchDarklyException("LDClient.getForMobileKey() called with invalid keyName");
        }
        return instancesNow.get(keyName);
    }

    @VisibleForTesting
    protected LDClient(final Application application, @NonNull final LDConfig config) {
        this(application, config, LDConfig.primaryEnvironmentName);
    }

    @VisibleForTesting
    protected LDClient(final Application application, @NonNull final LDConfig config, final String environmentName) {
        this.logger = LDLogger.withAdapter(config.getLogAdapter(), config.getLoggerName());
        logger.info("Creating LaunchDarkly client. Version: {}", BuildConfig.VERSION_NAME);
        this.config = config;
        this.application = application;
        String sdkKey = config.getMobileKeys().get(environmentName);
        FeatureFetcher fetcher = HttpFeatureFlagFetcher.newInstance(application, config, environmentName, logger);
        OkHttpClient sharedEventClient = makeSharedEventClient();
        if (config.getDiagnosticOptOut()) {
            this.diagnosticStore = null;
            this.diagnosticEventProcessor = null;
        } else {
            this.diagnosticStore = new DiagnosticStore(application, sdkKey);
            this.diagnosticEventProcessor = new DiagnosticEventProcessor(config, environmentName, diagnosticStore, application,
                    sharedEventClient, logger);
        }
        this.userManager = DefaultUserManager.newInstance(application, fetcher, environmentName, sdkKey, config.getMaxCachedUsers(),
                logger);

        eventProcessor = new DefaultEventProcessor(application, config, userManager.getSummaryEventStore(), environmentName,
                diagnosticStore, sharedEventClient, logger);
        connectivityManager = new ConnectivityManager(application, config, eventProcessor, userManager, environmentName,
                diagnosticEventProcessor, diagnosticStore, logger);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityReceiver = new ConnectivityReceiver();
            IntentFilter filter = new IntentFilter(ConnectivityReceiver.CONNECTIVITY_CHANGE);
            application.registerReceiver(connectivityReceiver, filter);
        }
    }

    private OkHttpClient makeSharedEventClient() {
        return new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(1, config.getEventsFlushIntervalMillis() * 2, TimeUnit.MILLISECONDS))
                .connectTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public void trackMetric(String eventName, LDValue data, double metricValue) {
        trackInternal(eventName, data, metricValue);
    }
    
    @Override
    public void trackData(String eventName, LDValue data) {
        trackInternal(eventName, data, null);
    }

    @Override
    public void track(String eventName) {
        trackInternal(eventName, null, null);
    }

    private void trackInternal(String eventName, LDValue data, Double metricValue) {
        sendEvent(new CustomEvent(eventName, userManager.getCurrentUser(), data, metricValue, config.inlineUsersInEvents()));
    }

    @Override
    public Future<Void> identify(LDUser user) {
        if (user == null) {
            return new LDFailedFuture<>(new LaunchDarklyException("User cannot be null"));
        }
        if (user.getKey() == null) {
            logger.warn("identify called with null user or null user key!");
        }
        return identifyInstances(customizeUser(user));
    }

    private @NonNull Map<String, LDClient> getInstancesIfTheyIncludeThisClient() {
        // Using this method ensures that 1. we are operating on an atomic snapshot of the
        // instances (in the unlikely case that they get closed & recreated right around now) and
        // 2. we do *not* operate on these instances if the current client is not one of them (i.e.
        // if it's already been closed). This method is guaranteed never to return null.
        Map<String, LDClient> ret = instances;
        if (ret != null) {
            for (LDClient c: ret.values()) {
                if (c == this) {
                    return ret;
                }
            }
        }
        return Collections.emptyMap();
    }

    private void identifyInternal(@NonNull LDUser user,
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

    private Future<Void> identifyInstances(@NonNull LDUser user) {
        final LDAwaitFuture<Void> resultFuture = new LDAwaitFuture<>();
        final Map<String, LDClient> instancesNow = getInstancesIfTheyIncludeThisClient();
        final AtomicInteger identifyCounter = new AtomicInteger(instancesNow.size());
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

        for (LDClient client : instancesNow.values()) {
            client.identifyInternal(user, completeWhenCounterZero);
        }

        return resultFuture;
    }

    @Override
    public Map<String, LDValue> allFlags() {
        Collection<Flag> allFlags = userManager.getCurrentUserFlagStore().getAllFlags();
        HashMap<String, LDValue> flagValues = new HashMap<>();
        for (Flag flag: allFlags) {
            if (!flag.isDeleted()) {
                flagValues.put(flag.getKey(), flag.getValue());
            }
        }
        return flagValues;
    }

    @Override
    public boolean boolVariation(@NonNull String key, boolean defaultValue) {
        return variationDetailInternal(key, LDValue.of(defaultValue), true, false).getValue().booleanValue();
    }

    @Override
    public EvaluationDetail<Boolean> boolVariationDetail(@NonNull String key, boolean defaultValue) {
        return convertDetailType(variationDetailInternal(key, LDValue.of(defaultValue), true, true), LDValue.Convert.Boolean);
    }

    @Override
    public int intVariation(@NonNull String key, int defaultValue) {
        return variationDetailInternal(key, LDValue.of(defaultValue), true, false).getValue().intValue();
    }

    @Override
    public EvaluationDetail<Integer> intVariationDetail(@NonNull String key, int defaultValue) {
        return convertDetailType(variationDetailInternal(key, LDValue.of(defaultValue), true, true), LDValue.Convert.Integer);
    }

    @Override
    public double doubleVariation(String flagKey, double defaultValue) {
        return variationDetailInternal(flagKey, LDValue.of(defaultValue), true, false).getValue().doubleValue();
    }

    @Override
    public EvaluationDetail<Double> doubleVariationDetail(String flagKey, double defaultValue) {
        return convertDetailType(variationDetailInternal(flagKey, LDValue.of(defaultValue), true, true), LDValue.Convert.Double);
    }

    @Override
    public String stringVariation(@NonNull String key, String defaultValue) {
        return variationDetailInternal(key, LDValue.of(defaultValue), true, false).getValue().stringValue();
    }

    @Override
    public EvaluationDetail<String> stringVariationDetail(@NonNull String key, String defaultValue) {
        return convertDetailType(variationDetailInternal(key, LDValue.of(defaultValue), true, true), LDValue.Convert.String);
    }

    @Override
    public LDValue jsonValueVariation(@NonNull String key, LDValue defaultValue) {
        return variationDetailInternal(key, LDValue.normalize(defaultValue), false, false).getValue();
    }

    @Override
    public EvaluationDetail<LDValue> jsonValueVariationDetail(@NonNull String key, LDValue defaultValue) {
        return variationDetailInternal(key, LDValue.normalize(defaultValue), false, true);
    }

    private <T> EvaluationDetail<T> convertDetailType(EvaluationDetail<LDValue> detail, LDValue.Converter<T> converter) {
        return EvaluationDetail.fromValue(converter.toType(detail.getValue()), detail.getVariationIndex(), detail.getReason());
    }

    private EvaluationDetail<LDValue> variationDetailInternal(@NonNull String key, @NonNull LDValue defaultValue, boolean checkType, boolean needsReason) {
        Flag flag = userManager.getCurrentUserFlagStore().getFlag(key);
        EvaluationDetail<LDValue> result;
        LDValue value = defaultValue;

        if (flag == null || flag.isDeleted()) {
            logger.info("Unknown feature flag \"{}\"; returning default value", key);
            result = EvaluationDetail.fromValue(defaultValue, EvaluationDetail.NO_VARIATION, EvaluationReason.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND));
        } else {
            value = flag.getValue();
            int variation = flag.getVariation() == null ? EvaluationDetail.NO_VARIATION : flag.getVariation();
            if (value.isNull()) {
                logger.warn("Feature flag \"{}\" retrieved with no value; returning default value", key);
                value = defaultValue;
                result = EvaluationDetail.fromValue(defaultValue, variation, flag.getReason());
            } else if (checkType && !defaultValue.isNull() && value.getType() != defaultValue.getType()) {
                logger.warn("Feature flag \"{}\" with type {} retrieved as {}; returning default value", key, value.getType(), defaultValue.getType());
                value = defaultValue;
                result = EvaluationDetail.fromValue(defaultValue, EvaluationDetail.NO_VARIATION, EvaluationReason.error(EvaluationReason.ErrorKind.WRONG_TYPE));
            } else {
                result = EvaluationDetail.fromValue(value, variation, flag.getReason());
            }
            sendFlagRequestEvent(key, flag, value, defaultValue, flag.isTrackReason() | needsReason ? result.getReason() : null);
        }

        logger.debug("returning variation: {} flagKey: {} user key: {}", result, key, userManager.getCurrentUser().getKey());
        updateSummaryEvents(key, flag, value, defaultValue);
        return result;
    }

    /**
     * Closes the client. This should only be called at the end of a client's lifecycle.
     *
     * @throws IOException declared by the Closeable interface, but will not be thrown by the client
     */
    @Override
    public void close() throws IOException {
        closeInstances();
    }

    private void closeInternal() {
        connectivityManager.shutdown();
        eventProcessor.close();
        
        if (connectivityReceiver != null) {
            application.unregisterReceiver(connectivityReceiver);
            connectivityReceiver = null;
        }
    }

    private void closeInstances() {
        Iterable<LDClient> oldClients;
        synchronized (initLock) {
            oldClients = getInstancesIfTheyIncludeThisClient().values();
            instances = null;
        }
        for (LDClient client : oldClients) {
            client.closeInternal();
        }
        sharedLogger = null;
    }

    @Override
    public void flush() {
        for (LDClient client : getInstancesIfTheyIncludeThisClient().values()) {
           client.flushInternal();
        }
    }

    private void flushInternal() {
        eventProcessor.flush();
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
    public void setOffline() {
        for (LDClient client : getInstancesIfTheyIncludeThisClient().values()) {
            client.setOfflineInternal();
        }
    }

    private void setOfflineInternal() {
        connectivityManager.setOffline();
    }

    @Override
    public void setOnline() {
        for (LDClient client : getInstancesIfTheyIncludeThisClient().values()) {
            client.setOnlineStatusInternal();
        }
    }

    private void setOnlineStatusInternal() {
        connectivityManager.setOnline();
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
        sendEvent(new AliasEvent(customizeUser(user), customizeUser(previousUser)));
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

    private void sendFlagRequestEvent(String flagKey, Flag flag, LDValue value, LDValue defaultValue, EvaluationReason reason) {
        int version = flag.getVersionForEvents();
        Integer variation = flag.getVariation();
        if (flag.isTrackEvents()) {
            sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser(), value, defaultValue, version,
                    variation, reason, config.inlineUsersInEvents(), false));
        } else {
            Long debugEventsUntilDate = flag.getDebugEventsUntilDate();
            if (debugEventsUntilDate != null) {
                long serverTimeMs = eventProcessor.getCurrentTimeMs();
                if (debugEventsUntilDate > System.currentTimeMillis() && debugEventsUntilDate > serverTimeMs) {
                    sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser(), value, defaultValue, version,
                            variation, reason, false, true));
                }
            }
        }
    }

    private void sendEvent(Event event) {
        if (!connectivityManager.isOffline()) {
            boolean processed = eventProcessor.sendEvent(event);
            if (!processed) {
                logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
                if (diagnosticStore != null) {
                    diagnosticStore.incrementDroppedEventCount();
                }
            }
        }
    }

    /**
     * Updates the internal representation of a summary event, either adding a new field or updating the existing count.
     * Nothing is sent to the server.
     *
     * @param flagKey      The flagKey that will be updated
     * @param flag         The stored flag used in the evaluation of the flagKey
     * @param result       The value that was returned in the evaluation of the flagKey
     * @param defaultValue The default value used in the evaluation of the flagKey
     */
    private void updateSummaryEvents(String flagKey, Flag flag, LDValue result, LDValue defaultValue) {
        result = LDValue.normalize(result);
        defaultValue = LDValue.normalize(defaultValue);
        Integer version = flag == null ? null : flag.getVersionForEvents();
        Integer variation = flag == null ? null : flag.getVariation();
        userManager.getSummaryEventStore().addOrUpdateEvent(flagKey, result, defaultValue, version, variation);
    }

    static void triggerPollInstances() {
        if (instances == null) {
            getSharedLogger().warn("Cannot perform poll when LDClient has not been initialized!");
            return;
        }
        for (LDClient instance : instances.values()) {
            instance.triggerPoll();
        }
    }

    static void onNetworkConnectivityChangeInstances(boolean network) {
        if (instances == null) {
            getSharedLogger().error("Tried to update LDClients with network connectivity status, but LDClient has not yet been initialized.");
            return;
        }
        for (LDClient instance : instances.values()) {
            instance.onNetworkConnectivityChange(network);
        }
    }

    private static void initSharedLogger(LDConfig config) {
        synchronized (initLock) {
            // We initialize the shared logger lazily because, until the first time init() is called, because
            // we don't know what the log adapter should be until there's a configuration.
            if (sharedLogger == null) {
                sharedLogger = LDLogger.withAdapter(config.getLogAdapter(), config.getLoggerName());
            }
        }
    }

    static LDLogger getSharedLogger() {
        LDLogger logger = sharedLogger;
        if (logger != null) {
            return logger;
        }
        return LDLogger.none();
    }

    @VisibleForTesting
    SummaryEventStore getSummaryEventStore() {
        return userManager.getSummaryEventStore();
    }
}
