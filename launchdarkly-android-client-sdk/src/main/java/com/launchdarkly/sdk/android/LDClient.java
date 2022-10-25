package com.launchdarkly.sdk.android;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;
import com.launchdarkly.sdk.internal.events.DefaultEventProcessor;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventsConfiguration;
import com.launchdarkly.sdk.internal.http.HttpProperties;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

/**
 * Client for accessing LaunchDarkly's Feature Flag system. This class enforces a singleton pattern.
 * The main entry point is the {@link #init(Application, LDConfig, LDContext)} method.
 * <p>
 * Like all client-side LaunchDarkly SDKs, the {@code LDClient} always has a single current
 * {@link LDContext} (evaluation context). You specify this context at initialization time, and you
 * can change it later with {@link LDClient#identify(LDContext)}. All subsequent calls to evaluation
 * methods like {@link LDClient#boolVariation} refer to the flag values for the current context.
 * <p>
 * Normally, the SDK uses the exact context that you have specified in th {@link LDContext}.
 * However, you can also tell the SDK to generate a randomized identifier and use this as the
 * context's {@code key}; see {@link LDConfig.Builder#generateAnonymousKeys(boolean)}.
 */
public class LDClient implements LDClientInterface, Closeable {
    // A map of each LDClient (one per environment), or null if `init` hasn't been called yet.
    // Will only be set once, during initialization, and the map is considered immutable.
    static volatile Map<String, LDClient> instances = null;
    private static volatile PlatformState sharedPlatformState;
    private static volatile TaskExecutor sharedTaskExecutor;
    private static volatile ContextDecorator contextDecorator;

    // A lock to ensure calls to `init()` are serialized.
    static Object initLock = new Object();

    private static volatile LDLogger sharedLogger;

    private final PlatformState platformState;
    private final TaskExecutor taskExecutor;
    private final LDConfig config;
    private final ContextDataManager contextDataManager;
    private final DefaultEventProcessor eventProcessor;
    private final ConnectivityManager connectivityManager;
    private final ClientStateImpl clientState;
    private final DiagnosticStore diagnosticStore;
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
     * @param application your Android application
     * @param config      configuration used to set up the client
     * @param context     the initial evaluation context; see {@link LDClient} for more information
     *                    about setting the context and optionally requesting a unique key for it
     * @return a {@link Future} which will complete once the client has been initialized
     * @see #init(Application, LDConfig, LDContext, int) 
     */
    public static Future<LDClient> init(@NonNull Application application,
                                        @NonNull LDConfig config,
                                        @NonNull LDContext context) {
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
        if (context == null || !context.isValid()) {
            return new LDFailedFuture<>(new LaunchDarklyException("Client initialization requires a valid evaluation context ("
                + (context == null ? "was null" : context.getError() + ")")));
        }

        LDLogger logger = initSharedLogger(config);

        final LDAwaitFuture<LDClient> resultFuture = new LDAwaitFuture<>();
        LDClient primaryClient;
        LDContext actualContext;

        // Acquire the `initLock` to ensure that if `init()` is called multiple times, we will only
        // initialize the client(s) once.
        synchronized (initLock) {
            if (instances != null) {
                logger.warn("LDClient.init() was called more than once! returning primary instance.");
                return new LDSuccessFuture<>(instances.get(LDConfig.primaryEnvironmentName));
            }

            sharedTaskExecutor = new AndroidTaskExecutor(application, logger);
            sharedPlatformState = new AndroidPlatformState(application, sharedTaskExecutor, logger);

            PersistentDataStore store = config.getPersistentDataStore() == null ?
                    new SharedPreferencesPersistentDataStore(application, logger) :
                    config.getPersistentDataStore();
            PersistentDataStoreWrapper persistentData = new PersistentDataStoreWrapper(
                    store,
                    logger
            );
            contextDecorator = new ContextDecorator(persistentData, config.isGenerateAnonymousKeys());

            Migration.migrateWhenNeeded(application, config);

            actualContext = contextDecorator.decorateContext(context, logger);

            // Create, but don't start, every LDClient instance
            final Map<String, LDClient> newInstances = new HashMap<>();

            LDClient createdPrimaryClient = null;
            for (Map.Entry<String, String> mobileKeys : config.getMobileKeys().entrySet()) {
                String envName = mobileKeys.getKey(), mobileKey = mobileKeys.getValue();
                try {
                    final LDClient instance = new LDClient(
                            sharedPlatformState,
                            sharedTaskExecutor,
                            persistentData.perEnvironmentData(mobileKey),
                            actualContext,
                            config,
                            mobileKey,
                            envName
                    );
                    newInstances.put(envName, instance);
                    if (mobileKey.equals(config.getMobileKey())) {
                        createdPrimaryClient = instance;
                    }
                } catch (LaunchDarklyException e) {
                    resultFuture.setException(e);
                    return resultFuture;
                }
            }
            primaryClient = createdPrimaryClient;
            // this indirect way of setting primaryClient is simply to make it easier to reference
            // it within an inner class below, since it is "effectively final"

            instances = newInstances;
        }

        final AtomicInteger initCounter = new AtomicInteger(config.getMobileKeys().size());
        LDUtil.ResultCallback<Void> completeWhenCounterZero = new LDUtil.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (initCounter.decrementAndGet() == 0) {
                    resultFuture.set(primaryClient);
                }
            }

            @Override
            public void onError(Throwable e) {
                resultFuture.setException(e);
            }
        };

        // Start up all instances
        for (final LDClient instance : instances.values()) {
            if (instance.connectivityManager.startUp(completeWhenCounterZero)) {
                instance.eventProcessor.sendEvent(new Event.Identify(
                        System.currentTimeMillis(),
                        actualContext
                ));
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
     * @param application      your Android application
     * @param config           configuration used to set up the client
     * @param context          the initial evaluation context; see {@link LDClient} for more
     *                         information about setting the context and optionally requesting a
     *                         unique key for it
     * @param startWaitSeconds maximum number of seconds to wait for the client to initialize
     * @return the primary LDClient instance
     * @see #init(Application, LDConfig, LDContext) 
     */
    public static LDClient init(Application application, LDConfig config, LDContext context, int startWaitSeconds) {
        initSharedLogger(config);
        getSharedLogger().info("Initializing Client and waiting up to {} for initialization to complete", startWaitSeconds);
        Future<LDClient> initFuture = init(application, config, context);
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
     * Returns the {@code LDClient} instance that was previously created with {@link #init(Application, LDConfig, LDContext, int)}
     * or {@link #init(Application, LDConfig, LDContext)}.
     * <p>
     * If you have configured multiple environments, this method returns the instance for the
     * primary environment.
     *
     * @return the singleton instance
     * @throws LaunchDarklyException if {@code init} has not been called
     * @see #getForMobileKey(String) 
     */
    public static LDClient get() throws LaunchDarklyException {
        if (instances == null) {
            getSharedLogger().error("LDClient.get() was called before init()!");
            throw new LaunchDarklyException("LDClient.get() was called before init()!");
        }
        return instances.get(LDConfig.primaryEnvironmentName);
    }

    /**
     * Returns the {@code LDClient} instance that was previously created with {@link #init(Application, LDConfig, LDContext, int)}
     * or {@link #init(Application, LDConfig, LDContext)}, for a specific environment.
     * <p>
     * This method is only relevant if you have configured multiple environments with
     * {@link LDConfig.Builder#secondaryMobileKeys(Map)}.
     * 
     * @return the singleton instance for the environment associated with the given name
     * @param keyName the name you gave to this environment (this must be one of the keys in the
     *                map you passed to {@link LDConfig.Builder#secondaryMobileKeys(Map)})
     * @throws LaunchDarklyException if {@code init} has not been called
     * @see #get()
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
    protected LDClient(
            @NonNull final PlatformState platformState,
            @NonNull final TaskExecutor taskExecutor,
            @NonNull PersistentDataStoreWrapper.PerEnvironmentData environmentStore,
            @NonNull LDContext initialContext,
            @NonNull final LDConfig config,
            @NonNull final String mobileKey,
            @NonNull final String environmentName
    ) throws LaunchDarklyException {
        this.logger = LDLogger.withAdapter(config.getLogAdapter(), config.getLoggerName());
        logger.info("Creating LaunchDarkly client. Version: {}", BuildConfig.VERSION_NAME);
        this.config = config;
        this.platformState = platformState;
        this.taskExecutor = taskExecutor;
        if (mobileKey == null) {
            throw new LaunchDarklyException("Mobile key cannot be null");
        }

        clientState = new ClientStateImpl(
                mobileKey,
                environmentName,
                logger,
                config.isOffline()
        );

        FeatureFetcher fetcher = HttpFeatureFlagFetcher.newInstance(platformState, config,
                clientState);
        if (config.getDiagnosticOptOut()) {
            this.diagnosticStore = null;
        } else {
            this.diagnosticStore = new DiagnosticStore(EventUtil.makeDiagnosticParams(config, mobileKey));
        }
        this.contextDataManager = new ContextDataManager(
                environmentStore,
                initialContext,
                config.getMaxCachedContexts(),
                taskExecutor,
                logger
        );

        HttpProperties httpProperties = LDUtil.makeHttpProperties(config, mobileKey);
        EventsConfiguration eventsConfig = EventUtil.makeEventsConfiguration(
                config,
                httpProperties,
                diagnosticStore,
                !platformState.isForeground(),
                logger
        );
        eventProcessor = new DefaultEventProcessor(
                eventsConfig,
                EventUtil.makeEventsTaskExecutor(),
                Thread.NORM_PRIORITY, // note, we may want to make this configurable as it is in java-server-sdk
                logger
        );

        connectivityManager = new ConnectivityManager(
                platformState,
                clientState,
                config,
                eventProcessor,
                contextDataManager,
                fetcher,
                environmentStore,
                taskExecutor,
                diagnosticStore
        );
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
        eventProcessor.sendEvent(new Event.Custom(
                System.currentTimeMillis(),
                eventName,
                contextDataManager.getCurrentContext(),
                data,
                metricValue
        ));
    }

    @Override
    public Future<Void> identify(LDContext context) {
        if (context == null) {
            return new LDFailedFuture<>(new LaunchDarklyException("Context cannot be null"));
        }
        if (!context.isValid()) {
            logger.warn("identify() was called with an invalid context: {}", context.getError());
            return new LDFailedFuture<>(new LaunchDarklyException("Invalid context: " + context.getError()));
        }
        return identifyInstances(contextDecorator.decorateContext(context, getSharedLogger()));
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

    private void identifyInternal(@NonNull LDContext context,
                                  LDUtil.ResultCallback<Void> onCompleteListener) {
        contextDataManager.setCurrentContext(context);
        connectivityManager.reloadData(onCompleteListener);
        eventProcessor.sendEvent(new Event.Identify(System.currentTimeMillis(), context));
    }

    private Future<Void> identifyInstances(@NonNull LDContext context) {
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
            client.identifyInternal(context, completeWhenCounterZero);
        }

        return resultFuture;
    }

    @Override
    public Map<String, LDValue> allFlags() {
        EnvironmentData allData = contextDataManager.getAllNonDeleted();
        HashMap<String, LDValue> flagValues = new HashMap<>();
        for (Flag flag: allData.values()) {
            flagValues.put(flag.getKey(), flag.getValue());
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
        Flag flag = contextDataManager.getNonDeletedFlag(key); // returns null for nonexistent *or* deleted flag
        EvaluationDetail<LDValue> result;
        LDValue value = defaultValue;

        if (flag == null) {
            logger.info("Unknown feature flag \"{}\"; returning default value", key);
            sendFlagRequestEvent(key, null, defaultValue, defaultValue, null);
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

        logger.debug("returning variation: {} flagKey: {} context key: {}", result, key,
                contextDataManager.getCurrentContext().getKey());
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

        synchronized (initLock) {
            sharedTaskExecutor.close();
            sharedTaskExecutor = null;
            sharedPlatformState.close();
            sharedPlatformState = null;
        }
    }

    private void closeInternal() {
        connectivityManager.shutdown();
        try {
            eventProcessor.close();
        } catch (Exception e) {}
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
        eventProcessor.flushAsync();
    }

    @VisibleForTesting
    void blockingFlush() {
        eventProcessor.flushBlocking();
    }

    @Override
    public boolean isInitialized() {
        return clientState.isForcedOffline() || connectivityManager.isInitialized();
    }

    @Override
    public boolean isOffline() {
        return clientState.isForcedOffline();
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
        contextDataManager.registerListener(flagKey, listener);
    }

    @Override
    public void unregisterFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener) {
        contextDataManager.unregisterListener(flagKey, listener);
    }

    @Override
    public boolean isDisableBackgroundPolling() {
        return config.isDisableBackgroundPolling();
    }

    public ConnectionInformation getConnectionInformation() {
        return connectivityManager.getConnectionInformation();
    }

    public void registerStatusListener(LDStatusListener statusListener) {
        connectivityManager.registerStatusListener(statusListener);
    }

    public void unregisterStatusListener(LDStatusListener statusListener) {
        connectivityManager.unregisterStatusListener(statusListener);
    }

    public void registerAllFlagsListener(LDAllFlagsListener allFlagsListener) {
        contextDataManager.registerAllFlagsListener(allFlagsListener);
    }

    public void unregisterAllFlagsListener(LDAllFlagsListener allFlagsListener) {
        contextDataManager.unregisterAllFlagsListener(allFlagsListener);
    }

    private void triggerPoll() {
        connectivityManager.triggerPoll();
    }

    @Override
    public String getVersion() {
        return BuildConfig.VERSION_NAME;
    }

    private void sendFlagRequestEvent(String flagKey, Flag flag, LDValue value, LDValue defaultValue, EvaluationReason reason) {
        eventProcessor.sendEvent(new Event.FeatureRequest(
                System.currentTimeMillis(),
                flagKey,
                contextDataManager.getCurrentContext(),
                flag == null ? -1 : flag.getVersionForEvents(),
                flag == null || flag.getVariation() == null ? -1 : flag.getVariation().intValue(),
                value,
                defaultValue,
                reason, // TODO
                null,
                flag != null && flag.isTrackEvents(),
                flag == null ? null : flag.getDebugEventsUntilDate(),
                false
        ));
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

    private static LDLogger initSharedLogger(LDConfig config) {
        synchronized (initLock) {
            // We initialize the shared logger lazily because, until the first time init() is called, because
            // we don't know what the log adapter should be until there's a configuration.
            if (sharedLogger == null) {
                sharedLogger = LDLogger.withAdapter(config.getLogAdapter(), config.getLoggerName());
            }
            return sharedLogger;
        }
    }

    static LDLogger getSharedLogger() {
        LDLogger logger = sharedLogger;
        if (logger != null) {
            return logger;
        }
        return LDLogger.none();
    }
}
