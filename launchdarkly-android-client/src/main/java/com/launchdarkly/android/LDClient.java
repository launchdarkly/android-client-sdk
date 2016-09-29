package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.launchdarkly.android.Util.isInternetConnected;

/**
 * Client for accessing LaunchDarkly's Feature Flag system. This class enforces a singleton pattern.
 * The main entry point is the {@link #init(Application, LDConfig, LDUser)} method.
 */
public class LDClient implements LDClientInterface, Closeable {
    private static final String TAG = "LaunchDarkly";

    private static final String INSTANCE_ID_KEY = "instanceId";
    // Upon client init will get set to a Unique id per installation used when creating anonymous users
    private static String instanceId = "UNKNOWN_ANDROID";
    private static LDClient instance = null;

    private final UserManager userManager;
    private final EventProcessor eventProcessor;
    private final StreamProcessor streamProcessor;
    private final FeatureFlagFetcher fetcher;

    private volatile boolean isOffline = false;

    /**
     * Initializes the singleton instance. The result is a {@link ListenableFuture} which
     * will complete once the client has been initialized with the latest feature flag values. For
     * immediate access to the Client (possibly with out of date feature flags), it is safe to ignore
     * the return value of this method, and afterward call {@link #get()}
     * <p/>
     * If the client has already been initialized, is configured for offline mode, or the device is
     * not connected to the internet, this method will return a {@link ListenableFuture} that is
     * already in the completed state.
     *
     * @param application Your Android application.
     * @param config      Configuration used to set up the client
     * @param user        The user used in evaluating feature flags
     * @return a {@link ListenableFuture} which will complete once the client has been initialized.
     */
    public static synchronized ListenableFuture<LDClient> init(Application application, LDConfig config, LDUser user) {
        SettableFuture<LDClient> settableFuture = SettableFuture.create();

        if (instance != null) {
            Log.w(TAG, "LDClient.init() was called more than once! returning existing instance.");
            settableFuture.set(instance);
            return settableFuture;
        }
        instance = new LDClient(application, config);
        instance.userManager.setCurrentUser(user);

        if (instance.isOffline() || !isInternetConnected(application)) {
            settableFuture.set(instance);
            return settableFuture;

        }
        instance.eventProcessor.start();
        ListenableFuture<Void> streamingFuture = instance.streamProcessor.start();
        instance.sendEvent(new IdentifyEvent(user));

        // Transform the StreamProcessor's initialization Future so its result is the instance:
        return Futures.transform(streamingFuture, new Function<Void, LDClient>() {
            @Override
            public LDClient apply(Void input) {
                return instance;
            }
        });
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
        Log.i(TAG, "Initializing Client and waiting up to " + startWaitSeconds + " for initialization to complete");
        ListenableFuture<LDClient> initFuture = init(application, config, user);
        try {
            return initFuture.get(startWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Exception during Client initialization", e);
        } catch (TimeoutException e) {
            Log.w(TAG, "Client did not successfully initialize within " + startWaitSeconds + " seconds. " +
                    "It could be taking longer than expected to start up");
        }
        return instance;
    }

    /**
     * @return the singleton instance.
     * @throws LaunchDarklyException if {@link #init(Application, LDConfig, LDUser)} has not been called.
     */
    public static LDClient get() throws LaunchDarklyException {
        if (instance == null) {
            Log.e(TAG, "LDClient.get() was called before init()!");
            throw new LaunchDarklyException("LDClient.get() was called before init()!");
        }
        return instance;
    }

    @VisibleForTesting
    protected LDClient(final Application application, LDConfig config) {
        Log.i(TAG, "Creating LaunchDarkly client. Version: " + BuildConfig.VERSION_NAME);
        this.isOffline = config.isOffline();

        SharedPreferences instanceIdSharedPrefs = application.getSharedPreferences("id", Context.MODE_PRIVATE);

        if (!instanceIdSharedPrefs.contains(INSTANCE_ID_KEY)) {
            String uuid = UUID.randomUUID().toString();
            Log.i(TAG, "Did not find existing instance id. Saving a new one");
            SharedPreferences.Editor editor = instanceIdSharedPrefs.edit();
            editor.putString(INSTANCE_ID_KEY, uuid);
            editor.apply();
        }

        instanceId = instanceIdSharedPrefs.getString(INSTANCE_ID_KEY, instanceId);
        Log.i(TAG, "Using instance id: " + instanceId);

        this.fetcher = HttpFeatureFlagFetcher.init(application, config);
        this.userManager = UserManager.init(application, fetcher);
        Foreground foreground = Foreground.get(application);
        Foreground.Listener foregroundListener = new Foreground.Listener() {
            @Override
            public void onBecameForeground() {
                BackgroundUpdater.stop(application);
                if (isInternetConnected(application)) {
                    startStreaming();
                }
            }

            @Override
            public void onBecameBackground() {
                BackgroundUpdater.start(application);
                stopStreaming();
            }
        };
        foreground.addListener(foregroundListener);

        this.streamProcessor = new StreamProcessor(config, userManager);
        eventProcessor = new EventProcessor(application, config);
    }

    /**
     * Tracks that a user performed an event.
     *
     * @param eventName the name of the event
     * @param data      a JSON object containing additional data associated with the event
     */
    @Override
    public void track(String eventName, JsonElement data) {
        sendEvent(new CustomEvent(eventName, userManager.getCurrentUser(), data));
    }

    /**
     * Tracks that a user performed an event.
     *
     * @param eventName the name of the event
     */
    @Override
    public void track(String eventName) {
        sendEvent(new CustomEvent(eventName, userManager.getCurrentUser(), null));
    }

    /**
     * Sets the current user, retrieves flags for that user, then sends an Identify Event to LaunchDarkly.
     * The 5 most recent users' flag settings are kept locally.
     *
     * @param user
     * @return Future whose success indicates this user's flag settings have been stored locally and are ready for evaluation.
     */
    @Override
    public synchronized ListenableFuture<Void> identify(LDUser user) {
        if (user == null) {
            return Futures.immediateFailedFuture(new LaunchDarklyException("User cannot be null"));
        }

        if (user.getKey() == null) {
            Log.w(TAG, "identify called with null user or null user key!");
        }
        ListenableFuture<Void> doneFuture = userManager.setCurrentUser(user);
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
    @Override
    public Boolean boolVariation(String flagKey, Boolean fallback) {
        boolean result = fallback;
        try {
            result = userManager.getCurrentUserSharedPrefs().getBoolean(flagKey, fallback);
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get boolean flag that exists as another type for key: "
                    + flagKey + " Returning fallback: " + fallback, cce);
        }
        sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback));
        Log.d(TAG, "boolVariation: returning variation: " + result + " flagKey: " + flagKey + " user key: " + userManager.getCurrentUser().getKeyAsString());
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
            Log.e(TAG, "Attempted to get integer flag that exists as another type for key: "
                    + flagKey + " Returning fallback: " + fallback, cce);
        }
        sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback));
        Log.d(TAG, "intVariation: returning variation: " + result + " flagKey: " + flagKey + " user key: " + userManager.getCurrentUser().getKeyAsString());
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
        float result = fallback;
        try {
            result = userManager.getCurrentUserSharedPrefs().getFloat(flagKey, fallback);
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get float flag that exists as another type for key: "
                    + flagKey + " Returning fallback: " + fallback, cce);
        }
        sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback));
        Log.d(TAG, "floatVariation: returning variation: " + result + " flagKey: " + flagKey + " user key: " + userManager.getCurrentUser().getKeyAsString());
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
            Log.e(TAG, "Attempted to get string flag that exists as another type for key: "
                    + flagKey + " Returning fallback: " + fallback, cce);
        }
        sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(fallback));
        Log.d(TAG, "stringVariation: returning variation: " + result + " flagKey: " + flagKey + " user key: " + userManager.getCurrentUser().getKeyAsString());
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
            Log.e(TAG, "Attempted to get json (string) flag that exists as another type for key: "
                    + flagKey + " Returning fallback: " + fallback, cce);
        }
        sendFlagRequestEvent(flagKey, result, fallback);
        Log.d(TAG, "jsonVariation: returning variation: " + result + " flagKey: " + flagKey + " user key: " + userManager.getCurrentUser().getKeyAsString());
        return result;
    }

    /**
     * Closes the client. This should only be called at the end of a client's lifecycle.
     *
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        streamProcessor.close();
        eventProcessor.close();
    }

    /**
     * Sends all pending events to LaunchDarkly.
     */
    @Override
    public void flush() {
        eventProcessor.flush();
    }

    @Override
    public boolean isInitialized() {
        return isOffline() || streamProcessor.isInitialized();
    }

    @Override
    public boolean isOffline() {
        return isOffline;
    }

    @Override
    public synchronized void setOffline() {
        Log.d(TAG, "Setting isOffline = true");
        isOffline = true;
        fetcher.setOffline();
        stopStreaming();
        eventProcessor.stop();
    }

    @Override
    public synchronized void setOnline() {
        Log.d(TAG, "Setting isOffline = false");
        this.isOffline = false;
        fetcher.setOnline();
        startStreaming();
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

    static String getInstanceId() {
        return instanceId;
    }

    void stopStreaming() {
        if (streamProcessor != null) {
            streamProcessor.stop();
        }
    }

    void startStreaming() {
        if (!isOffline && streamProcessor != null) {
            streamProcessor.start();
        }
    }

    private void sendFlagRequestEvent(String flagKey, JsonElement value, JsonElement fallback) {
        sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser(), value, fallback));
    }

    private void sendEvent(Event event) {
        if (!isOffline()) {
            boolean processed = eventProcessor.sendEvent(event);
            if (!processed) {
                Log.w(TAG, "Exceeded event queue capacity. Increase capacity to avoid dropping events.");
            }
        }
    }
}
