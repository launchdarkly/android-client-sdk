package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.launchdarkly.android.response.SummaryEventSharedPreferences;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import timber.log.Timber;

import static com.launchdarkly.android.Util.isInternetConnected;

/**
 * Client for accessing LaunchDarkly's Feature Flag system. This class enforces a singleton pattern.
 * The main entry point is the {@link #init(Application, LDConfig, LDUser)} method.
 */
public class LDClient implements LDClientInterface, Closeable {

    private static final String INSTANCE_ID_KEY = "instanceId";
    // Upon client init will get set to a Unique id per installation used when creating anonymous users
    private static String instanceId = "UNKNOWN_ANDROID";
    private static Map<String, LDClient> instances = null;

    private static final long MAX_RETRY_TIME_MS = 3600000; // 1 hour
    private static final long RETRY_TIME_MS = 1000; // 1 second

    private final WeakReference<Application> application;
    private final LDConfig config;
    private final UserManager userManager;
    private final EventProcessor eventProcessor;
    private final UpdateProcessor updateProcessor;
    private final FeatureFlagFetcher fetcher;
    private final Throttler throttler;

    private volatile boolean isOffline = false;
    private volatile boolean isAppForegrounded = true;

    public static final String primaryEnvironmentName = UUID.randomUUID().toString().replace("-", "");

    /**
     * Initializes the singleton/primary instance. The result is a {@link Future} which
     * will complete once the client has been initialized with the latest feature flag values. For
     * immediate access to the Client (possibly with out of date feature flags), it is safe to ignore
     * the return value of this method, and afterward call {@link #get()}
     * <p/>
     * If the client has already been initialized, is configured for offline mode, or the device is
     * not connected to the internet, this method will return a {@link Future} that is
     * already in the completed state.
     *
     * @param application Your Android application.
     * @param config      Configuration used to set up the client
     * @param user        The user used in evaluating feature flags
     * @return a {@link Future} which will complete once the client has been initialized.
     */
    public static synchronized Future<LDClient> init(@NonNull Application application, @NonNull LDConfig config, @NonNull LDUser user) {
        boolean applicationValid = validateParameter(application);
        boolean configValid = validateParameter(config);
        boolean userValid = validateParameter(user);
        if (!applicationValid) {
            return Futures.immediateFailedFuture(new LaunchDarklyException("Client initialization requires a valid application"));
        }
        if (!configValid) {
            return Futures.immediateFailedFuture(new LaunchDarklyException("Client initialization requires a valid configuration"));
        }
        if (!userValid) {
            return Futures.immediateFailedFuture(new LaunchDarklyException("Client initialization requires a valid user"));
        }

        SettableFuture<LDClient> settableFuture = SettableFuture.create();

        if (instances != null) {
            Timber.w("LDClient.init() was called more than once! returning existing instance.");
            settableFuture.set(instances.get(primaryEnvironmentName));
            return settableFuture;
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }

        boolean internetConnected = isInternetConnected(application);
        instances = new HashMap<>();

        if (config.getSecondaryMobileKeys() != null) {
            for (Map.Entry<String, String> secondaryKeys : config.getSecondaryMobileKeys().entrySet()) {
                final LDClient secondaryInstance = new LDClient(application, config, secondaryKeys.getKey());
                secondaryInstance.userManager.setCurrentUser(user);

                instances.put(secondaryKeys.getKey(), secondaryInstance);

                if (secondaryInstance.isOffline() || !internetConnected)
                    continue;

                secondaryInstance.eventProcessor.start();
                ListenableFuture<Void> initFuture = secondaryInstance.updateProcessor.start();
                secondaryInstance.sendEvent(new IdentifyEvent(user));
            }
        }

        final LDClient primaryInstance = new LDClient(application, config);
        primaryInstance.userManager.setCurrentUser(user);

        if (primaryInstance.isOffline() || !internetConnected) {
            settableFuture.set(primaryInstance);
            return settableFuture;
        }
        primaryInstance.eventProcessor.start();

        ListenableFuture<Void> initFuture = primaryInstance.updateProcessor.start();
        primaryInstance.sendEvent(new IdentifyEvent(user));

        instances.put(primaryEnvironmentName, primaryInstance);

        // Transform initFuture so its result is the instance:
        return Futures.transform(initFuture, new Function<Void, LDClient>() {
            @Override
            public LDClient apply(Void input) {
                return primaryInstance;
            }
        }, MoreExecutors.directExecutor());
    }

    public static synchronized Future<LDClient> getForMobileKey(String keyName) {
        SettableFuture<LDClient> settableFuture = SettableFuture.create();
        LDClient client = instances.get(keyName);

        if (client != null) {
            settableFuture.set(client);
            return settableFuture;
        } else {
            throw new NoSuchElementException();
        }
    }

    private static LDClient getForMobileKey(String keyName, int startWaitSeconds) {
        Future<LDClient> clientFuture = getForMobileKey(keyName);

        try {
            return clientFuture.get(startWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            Timber.e(e, "Exception during secondary instance retrieval");
        } catch (TimeoutException e) {
            Timber.w("Secondary instance was not retrieved within " + startWaitSeconds + " seconds. " +
                    "It could be taking longer than expected to start up");
        }
        return instances.get(keyName);
    }

    private static <T> boolean validateParameter(T parameter) {
        boolean parameterValid;
        try {
            Preconditions.checkNotNull(parameter);
            parameterValid = true;
        } catch (NullPointerException e) {
            parameterValid = false;
        }
        return parameterValid;
    }

    /**
     * Initializes the singleton instance and blocks for up to <code>startWaitSeconds</code> seconds
     * until the client has been initialized. If the client does not initialize within
     * <code>startWaitSeconds</code> seconds, it is returned anyway and can be used, but may not
     * have fetched the most recent feature flag values.
     *
     * @param application
     * @param config
     * @param user
     * @param startWaitSeconds
     * @return
     */
    public static synchronized LDClient init(Application application, LDConfig config, LDUser user, int startWaitSeconds) {
        Timber.i("Initializing Client and waiting up to " + startWaitSeconds + " for initialization to complete");
        Future<LDClient> initFuture = init(application, config, user);
        try {
            return initFuture.get(startWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            Timber.e(e, "Exception during Client initialization");
        } catch (TimeoutException e) {
            Timber.w("Client did not successfully initialize within " + startWaitSeconds + " seconds. " +
                    "It could be taking longer than expected to start up");
        }
        return instances.get(primaryEnvironmentName);
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
        return instances.get(primaryEnvironmentName);
    }

    @VisibleForTesting
    protected LDClient(final Application application, @NonNull final LDConfig config) {
        this(application, config, primaryEnvironmentName);
    }

    @VisibleForTesting
    protected LDClient(final Application application, @NonNull final LDConfig config, String environmentName) {
        Timber.i("Creating LaunchDarkly client. Version: %s", BuildConfig.VERSION_NAME);
        this.config = config;
        this.isOffline = config.isOffline();
        this.application = new WeakReference<>(application);

        SharedPreferences instanceIdSharedPrefs = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "id", Context.MODE_PRIVATE);

        if (!instanceIdSharedPrefs.contains(INSTANCE_ID_KEY)) {
            String uuid = UUID.randomUUID().toString();
            Timber.i("Did not find existing instance id. Saving a new one");
            SharedPreferences.Editor editor = instanceIdSharedPrefs.edit();
            editor.putString(INSTANCE_ID_KEY, uuid);
            editor.apply();
        }

        instanceId = instanceIdSharedPrefs.getString(INSTANCE_ID_KEY, instanceId);
        Timber.i("Using instance id: " + instanceId);

        this.fetcher = HttpFeatureFlagFetcher.init(application, config);
        this.userManager = UserManager.init(application, fetcher);
        Foreground foreground = Foreground.get(application);
        Foreground.Listener foregroundListener = new Foreground.Listener() {
            @Override
            public void onBecameForeground() {
                PollingUpdater.stop(application);
                isAppForegrounded = true;
                if (isInternetConnected(application)) {
                    startForegroundUpdating();
                }
            }

            @Override
            public void onBecameBackground() {
                stopForegroundUpdating();
                isAppForegrounded = false;
                startBackgroundPolling();
            }
        };
        foreground.addListener(foregroundListener);

        if (config.isStream()) {
            this.updateProcessor = new StreamUpdateProcessor(config, userManager);
        } else {
            Timber.i("Streaming is disabled. Starting LaunchDarkly Client in polling mode");
            this.updateProcessor = new PollingUpdateProcessor(application, userManager, config);
        }
        eventProcessor = new EventProcessor(application, config, userManager.getSummaryEventSharedPreferences());

        throttler = new Throttler(new Runnable() {
            @Override
            public void run() {
                setOnlineStatus();
            }
        }, RETRY_TIME_MS, MAX_RETRY_TIME_MS);
    }

    /**
     * Tracks that a user performed an event.
     *
     * @param eventName the name of the event
     * @param data      a JSON object containing additional data associated with the event
     */
    @Override
    public void track(String eventName, JsonElement data) {
        if (config.inlineUsersInEvents()) {
            sendEvent(new CustomEvent(eventName, userManager.getCurrentUser(), data));
        } else {
            sendEvent(new CustomEvent(eventName, userManager.getCurrentUser().getKeyAsString(), data));
        }
    }

    /**
     * Tracks that a user performed an event.
     *
     * @param eventName the name of the event
     */
    @Override
    public void track(String eventName) {
        if (config.inlineUsersInEvents()) {
            sendEvent(new CustomEvent(eventName, userManager.getCurrentUser(), null));
        } else {
            sendEvent(new CustomEvent(eventName, userManager.getCurrentUser().getKeyAsString(), null));
        }
    }

    /**
     * Sets the current user, retrieves flags for that user, then sends an Identify Event to LaunchDarkly.
     * The 5 most recent users' flag settings are kept locally.
     *
     * @param user
     * @return Future whose success indicates this user's flag settings have been stored locally and are ready for evaluation.
     */
    @Override
    public synchronized Future<Void> identify(LDUser user) {
        if (user == null) {
            return Futures.immediateFailedFuture(new LaunchDarklyException("User cannot be null"));
        }

        if (user.getKey() == null) {
            Timber.w("identify called with null user or null user key!");
        }

        ListenableFuture<Void> doneFuture;
        userManager.setCurrentUser(user);

        if (!config.isStream()) {
            doneFuture = userManager.updateCurrentUser();
        } else {
            doneFuture = updateProcessor.restart();
        }

        sendEvent(new IdentifyEvent(user));

        return doneFuture;
    }

    /**
     * Returns a map of all feature flags for the current user. No events are sent to LaunchDarkly.
     *
     * @return
     */
    @Override
    public Map<String, ?> allFlags() {
        return userManager.getCurrentUserSharedPrefs().getAll();
    }

    /**
     * Returns the flag value for the current user. Returns <code>fallback</code> when one of the following occurs:
     * <ol>
     * <li>Flag is missing</li>
     * <li>The flag is not of a boolean type</li>
     * <li>Any other error</li>
     * </ol>
     *
     * @param flagKey
     * @param fallback
     * @return
     */
    @SuppressWarnings("ConstantConditions")
    @Override
    public Boolean boolVariation(String flagKey, Boolean fallback) {
        Boolean result = fallback;
        try {
            result = userManager.getCurrentUserSharedPrefs().getBoolean(flagKey, fallback);
        } catch (ClassCastException cce) {
            Timber.e(cce, "Attempted to get boolean flag that exists as another type for key: "
                    + flagKey + " Returning fallback: " + fallback);
        } catch (NullPointerException npe) {
            Timber.e(npe, "Attempted to get boolean flag with a default null value for key: "
                    + flagKey + " Returning fallback: " + fallback);
        }
        int version = userManager.getFlagResponseSharedPreferences().getVersionForEvents(flagKey);
        int variation = userManager.getFlagResponseSharedPreferences().getStoredVariation(flagKey);
        if (result == null && fallback == null) {
            updateSummaryEvents(flagKey, null, null);
            sendFlagRequestEvent(flagKey, JsonNull.INSTANCE, JsonNull.INSTANCE, version, variation);
        } else if (result == null) {
            updateSummaryEvents(flagKey, null, new JsonPrimitive(fallback));
            sendFlagRequestEvent(flagKey, JsonNull.INSTANCE, new JsonPrimitive(fallback), version, variation);
        } else if (fallback == null) {
            updateSummaryEvents(flagKey, new JsonPrimitive(result), null);
            sendFlagRequestEvent(flagKey, new JsonPrimitive(result), JsonNull.INSTANCE, version, variation);
        } else {
            updateSummaryEvents(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback));
            sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback), version, variation);
        }
        Timber.d("boolVariation: returning variation: " + result + " flagKey: " + flagKey + " user key: " + userManager.getCurrentUser().getKeyAsString());
        return result;
    }

    /**
     * Returns the flag value for the current user. Returns <code>fallback</code> when one of the following occurs:
     * <ol>
     * <li>Flag is missing</li>
     * <li>The flag is not of an integer type</li>
     * <li>Any other error</li>
     * </ol>
     *
     * @param flagKey
     * @param fallback
     * @return
     */
    @Override
    public Integer intVariation(String flagKey, Integer fallback) {
        Integer result = fallback;
        try {
            result = (int) userManager.getCurrentUserSharedPrefs().getFloat(flagKey, fallback);
        } catch (ClassCastException cce) {
            Timber.e(cce, "Attempted to get integer flag that exists as another type for key: "
                    + flagKey + " Returning fallback: " + fallback);
        } catch (NullPointerException npe) {
            Timber.e(npe, "Attempted to get integer flag with a default null value for key: "
                    + flagKey + " Returning fallback: " + fallback);
        }
        int version = userManager.getFlagResponseSharedPreferences().getVersionForEvents(flagKey);
        int variation = userManager.getFlagResponseSharedPreferences().getStoredVariation(flagKey);
        if (result == null && fallback == null) {
            updateSummaryEvents(flagKey, null, null);
            sendFlagRequestEvent(flagKey, JsonNull.INSTANCE, JsonNull.INSTANCE, version, variation);
        } else if (result == null) {
            updateSummaryEvents(flagKey, null, new JsonPrimitive(fallback));
            sendFlagRequestEvent(flagKey, JsonNull.INSTANCE, new JsonPrimitive(fallback), version, variation);
        } else if (fallback == null) {
            updateSummaryEvents(flagKey, new JsonPrimitive(result), null);
            sendFlagRequestEvent(flagKey, new JsonPrimitive(result), JsonNull.INSTANCE, version, variation);
        } else {
            updateSummaryEvents(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback));
            sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback), version, variation);
        }
        Timber.d("intVariation: returning variation: " + result + " flagKey: " + flagKey + " user key: " + userManager.getCurrentUser().getKeyAsString());
        return result;
    }

    /**
     * Returns the flag value for the current user. Returns <code>fallback</code> when one of the following occurs:
     * <ol>
     * <li>Flag is missing</li>
     * <li>The flag is not of a float type</li>
     * <li>Any other error</li>
     * </ol>
     *
     * @param flagKey
     * @param fallback
     * @return
     */
    @Override
    public Float floatVariation(String flagKey, Float fallback) {
        Float result = fallback;
        try {
            result = userManager.getCurrentUserSharedPrefs().getFloat(flagKey, fallback);
        } catch (ClassCastException cce) {
            Timber.e(cce, "Attempted to get float flag that exists as another type for key: "
                    + flagKey + " Returning fallback: " + fallback);
        } catch (NullPointerException npe) {
            Timber.e(npe, "Attempted to get float flag with a default null value for key: "
                    + flagKey + " Returning fallback: " + fallback);
        }
        int version = userManager.getFlagResponseSharedPreferences().getVersionForEvents(flagKey);
        int variation = userManager.getFlagResponseSharedPreferences().getStoredVariation(flagKey);
        if (result == null && fallback == null) {
            updateSummaryEvents(flagKey, null, null);
            sendFlagRequestEvent(flagKey, JsonNull.INSTANCE, JsonNull.INSTANCE, version, variation);
        } else if (result == null) {
            updateSummaryEvents(flagKey, null, new JsonPrimitive(fallback));
            sendFlagRequestEvent(flagKey, JsonNull.INSTANCE, new JsonPrimitive(fallback), version, variation);
        } else if (fallback == null) {
            updateSummaryEvents(flagKey, new JsonPrimitive(result), null);
            sendFlagRequestEvent(flagKey, new JsonPrimitive(result), JsonNull.INSTANCE, version, variation);
        } else {
            updateSummaryEvents(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback));
            sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback), version, variation);
        }
        Timber.d("floatVariation: returning variation: " + result + " flagKey: " + flagKey + " user key: " + userManager.getCurrentUser().getKeyAsString());
        return result;
    }

    /**
     * Returns the flag value for the current user. Returns <code>fallback</code> when one of the following occurs:
     * <ol>
     * <li>Flag is missing</li>
     * <li>The flag is not of a String type</li>
     * <li>Any other error</li>
     * </ol>
     *
     * @param flagKey
     * @param fallback
     * @return
     */
    @Override
    public String stringVariation(String flagKey, String fallback) {
        String result = fallback;
        try {
            result = userManager.getCurrentUserSharedPrefs().getString(flagKey, fallback);
        } catch (ClassCastException cce) {
            Timber.e(cce, "Attempted to get string flag that exists as another type for key: "
                    + flagKey + " Returning fallback: " + fallback);
        } catch (NullPointerException npe) {
            Timber.e(npe, "Attempted to get string flag with a default null value for key: "
                    + flagKey + " Returning fallback: " + fallback);
        }
        int version = userManager.getFlagResponseSharedPreferences().getVersionForEvents(flagKey);
        int variation = userManager.getFlagResponseSharedPreferences().getStoredVariation(flagKey);
        if (result == null && fallback == null) {
            updateSummaryEvents(flagKey, null, null);
            sendFlagRequestEvent(flagKey, JsonNull.INSTANCE, JsonNull.INSTANCE, version, variation);
        } else if (result == null) {
            updateSummaryEvents(flagKey, null, new JsonPrimitive(fallback));
            sendFlagRequestEvent(flagKey, JsonNull.INSTANCE, new JsonPrimitive(fallback), version, variation);
        } else if (fallback == null) {
            updateSummaryEvents(flagKey, new JsonPrimitive(result), null);
            sendFlagRequestEvent(flagKey, new JsonPrimitive(result), JsonNull.INSTANCE, version, variation);
        } else {
            updateSummaryEvents(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback));
            sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback), version, variation);
        }
        Timber.d("stringVariation: returning variation: " + result + " flagKey: " + flagKey + " user key: " + userManager.getCurrentUser().getKeyAsString());
        return result;
    }

    /**
     * Returns the flag value for the current user. Returns <code>fallback</code> when one of the following occurs:
     * <ol>
     * <li>Flag is missing</li>
     * <li>The flag is not valid JSON</li>
     * <li>Any other error</li>
     * </ol>
     *
     * @param flagKey
     * @param fallback
     * @return
     */
    @Override
    public JsonElement jsonVariation(String flagKey, JsonElement fallback) {
        JsonElement result = fallback;
        try {
            String stringResult = userManager.getCurrentUserSharedPrefs().getString(flagKey, null);
            if (stringResult != null) {
                result = new JsonParser().parse(stringResult);
            }
        } catch (ClassCastException cce) {
            Timber.e(cce, "Attempted to get json (string) flag that exists as another type for key: "
                    + flagKey + " Returning fallback: " + fallback);
        } catch (NullPointerException npe) {
            Timber.e(npe, "Attempted to get json (string flag with a default null value for key: "
                    + flagKey + " Returning fallback: " + fallback);
        } catch (JsonSyntaxException jse) {
            Timber.e(jse, "Attempted to get json (string flag that exists as another type for key: " +
                    flagKey + " Returning fallback: " + fallback);
        }
        int version = userManager.getFlagResponseSharedPreferences().getVersionForEvents(flagKey);
        int variation = userManager.getFlagResponseSharedPreferences().getStoredVariation(flagKey);
        updateSummaryEvents(flagKey, result, fallback);
        sendFlagRequestEvent(flagKey, result, fallback, version, variation);
        Timber.d("jsonVariation: returning variation: " + result + " flagKey: " + flagKey + " user key: " + userManager.getCurrentUser().getKeyAsString());
        return result;
    }

    /**
     * Closes the client. This should only be called at the end of a client's lifecycle.
     *
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        LDClient.closeInstances();
    }

    private void closeInternal() throws IOException {
        updateProcessor.stop();
        eventProcessor.close();
    }

    public static void closeInstances() {
        for (LDClient client : instances.values()) {
            try {
                client.closeInternal();
            } catch (IOException e) {
                // TODO(gavwhela) handle IOException
                e.printStackTrace();
            }
        }
    }

    /**
     * Sends all pending events to LaunchDarkly.
     */
    @Override
    public void flush() {
        LDClient.flushInstances();
    }

    private void flushInternal() {
        eventProcessor.flush();
    }

    public static void flushInstances() {
        for (LDClient client : instances.values()) {
            client.flushInternal();
        }

    }

    @Override
    public boolean isInitialized() {
        return isOffline() || updateProcessor.isInitialized();
    }

    @Override
    public boolean isOffline() {
        return isOffline;
    }

    /**
     * Shuts down any network connections maintained by the client and puts the client in offline
     * mode, preventing the client from opening new network connections until
     * <code>setOnline()</code> is called.
     * <p/>
     * Note: The client automatically monitors the device's network connectivity and app foreground
     * status, so calling <code>setOffline()</code> or <code>setOnline()</code> is normally
     * unnecessary in most situations.
     */
    @Override
    public synchronized void setOffline() {
        LDClient.setInstancesOffline();
    }

    private synchronized void setOfflineInternal() {
        Timber.d("Setting isOffline = true");
        throttler.cancel();
        isOffline = true;
        fetcher.setOffline();
        stopForegroundUpdating();
        eventProcessor.stop();
    }

    public synchronized static void setInstancesOffline() {
        for (LDClient client : instances.values()) {
            client.setOfflineInternal();
        }
    }

    /**
     * Restores network connectivity for the client, if the client was previously in offline mode.
     * This operation may be throttled if it is called too frequently.
     * <p/>
     * Note: The client automatically monitors the device's network connectivity and app foreground
     * status, so calling <code>setOffline()</code> or <code>setOnline()</code> is normally
     * unnecessary in most situations.
     */
    @Override
    public synchronized void setOnline() {
        throttler.attemptRun();
    }

    private void setOnlineStatus() {
        Timber.d("Setting isOffline = false");
        isOffline = false;
        fetcher.setOnline();
        if (isAppForegrounded) {
            startForegroundUpdating();
        } else {
            startBackgroundPolling();
        }
        eventProcessor.start();
    }

    /**
     * Registers a {@link FeatureFlagChangeListener} to be called when the <code>flagKey</code> changes
     * from its current value. If the feature flag is deleted, the <code>listener</code> will be unregistered.
     *
     * @param flagKey
     * @param listener
     */
    @Override
    public void registerFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener) {
        userManager.registerListener(flagKey, listener);
    }

    /**
     * Unregisters a {@link FeatureFlagChangeListener} for the <code>flagKey</code>
     *
     * @param flagKey
     * @param listener
     */
    @Override
    public void unregisterFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener) {
        userManager.unregisterListener(flagKey, listener);
    }

    @Override
    public boolean isDisableBackgroundPolling() {
        return config.isDisableBackgroundPolling();
    }

    static String getInstanceId() {
        return instanceId;
    }

    void stopForegroundUpdating() {
        updateProcessor.stop();
    }

    void startForegroundUpdating() {
        if (!isOffline()) {
            updateProcessor.start();
        }
    }

    private void sendFlagRequestEvent(String flagKey, JsonElement value, JsonElement fallback, int version, int variation) {
        if (userManager.getFlagResponseSharedPreferences().getStoredTrackEvents(flagKey)) {
            if (config.inlineUsersInEvents()) {
                sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser(), value, fallback, version, variation));
            } else {
                sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser().getKeyAsString(), value, fallback, version, variation));
            }
        } else {
            Long debugEventsUntilDate = userManager.getFlagResponseSharedPreferences().getStoredDebugEventsUntilDate(flagKey);
            if (debugEventsUntilDate != null) {
                long serverTimeMs = eventProcessor.getCurrentTimeMs();
                if (debugEventsUntilDate > System.currentTimeMillis() && debugEventsUntilDate > serverTimeMs) {
                    sendEvent(new DebugEvent(flagKey, userManager.getCurrentUser(), value, fallback, version, variation));
                }
            }
        }

        sendSummaryEvent();
    }

    void startBackgroundPolling() {
        Application application = this.application.get();
        if (application != null && !config.isDisableBackgroundPolling() && !isOffline() && isInternetConnected(application)) {
            PollingUpdater.startBackgroundPolling(application);
        }
    }

    private void sendEvent(Event event) {
        if (!isOffline()) {
            boolean processed = eventProcessor.sendEvent(event);
            if (!processed) {
                Timber.w("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
            }
        }
    }

    /**
     * Updates the internal representation of a summary event, either adding a new field or updating the existing count.
     * Nothing is sent to the server.
     *
     * @param flagKey  The flagKey that will be updated
     * @param result   The value that was returned in the evaluation of the flagKey
     * @param fallback The fallback value used in the evaluation of the flagKey
     */
    private void updateSummaryEvents(String flagKey, JsonElement result, JsonElement fallback) {
        int version = userManager.getFlagResponseSharedPreferences().getVersionForEvents(flagKey);
        int variation = userManager.getFlagResponseSharedPreferences().getStoredVariation(flagKey);
        boolean isUnknown = !userManager.getFlagResponseSharedPreferences().containsKey(flagKey);

        userManager.getSummaryEventSharedPreferences().addOrUpdateEvent(flagKey, result, fallback, version, variation, isUnknown);
    }

    /**
     * Updates the cached summary event that will be sent to the server with the next batch of events.
     */
    private void sendSummaryEvent() {
        JsonObject features = userManager.getSummaryEventSharedPreferences().getFeaturesJsonObject();
        if (features.keySet().size() == 0) {
            return;
        }
        Long startDate = null;
        for (String key : features.keySet()) {
            JsonObject asJsonObject = features.get(key).getAsJsonObject();
            if (asJsonObject.has("startDate")) {
                startDate = asJsonObject.get("startDate").getAsLong();
                asJsonObject.remove("startDate");
                break;
            }
        }
        SummaryEvent summaryEvent = new SummaryEvent(startDate, System.currentTimeMillis(), features);
        Timber.d("Sending Summary Event: %s", summaryEvent.toString());
        eventProcessor.setSummaryEvent(summaryEvent);
    }

    @VisibleForTesting
    public void clearSummaryEventSharedPreferences() {
        userManager.getSummaryEventSharedPreferences().clear();
    }

    @VisibleForTesting
    public SummaryEventSharedPreferences getSummaryEventSharedPreferences() {
        return userManager.getSummaryEventSharedPreferences();
    }
}
