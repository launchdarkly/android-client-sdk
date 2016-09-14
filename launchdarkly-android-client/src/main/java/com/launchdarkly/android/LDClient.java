package com.launchdarkly.android;

import android.app.Application;
import android.util.Log;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Future;

import static com.launchdarkly.android.Util.isInternetConnected;

public class LDClient implements LDClientInterface, Closeable {
    private static final String TAG = "LaunchDarkly";
    private static LDClient instance = null;
    private final LDConfig config;
    private final UserManager userManager;

    private EventProcessor eventProcessor;
    private StreamProcessor streamProcessor;
    private FeatureFlagUpdater updater;

    /**
     * Initializes the singleton instance.
     *
     * @param application Your Android application.
     * @param config      Configuration used to set up the client
     * @param user        The user to
     * @return
     */
    public static LDClient init(Application application, LDConfig config, LDUser user) {
        if (instance != null) {
            Log.w(TAG, "LDClient.init() was called more than once! returning existing instance.");
            return instance;
        }
        instance = new LDClient(application, config, user);
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
    protected LDClient(final Application application, LDConfig config, LDUser user) {
        Log.i(TAG, "Starting LaunchDarkly client");
        this.config = config;
        this.userManager = new UserManager(application, user);
        userManager.setCurrentUser(user);

        if (!isOffline()) {
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

            this.updater = FeatureFlagUpdater.init(application, config, userManager);
            this.streamProcessor = new StreamProcessor(config, updater);
            streamProcessor.start();
            eventProcessor = new EventProcessor(application, config);
            sendEvent(new IdentifyEvent(user));
        }
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
    public synchronized Future<Void> identify(LDUser user) {
        if (user == null || user.getKey() == null) {
            Log.w(TAG, "identify called with null user or null user key!");
        }
        userManager.setCurrentUser(user);
        Future<Void> doneFuture = updater.update();
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
     * Returns the flag value for the current user. Returns defaultValue when one of the following occurs:
     * 1. Flag is missing
     * 2. The flag is not of a boolean type.
     * 3. Any other error.
     *
     * @param flagKey
     * @param defaultValue
     * @return
     */
    @Override
    public Boolean boolVariation(String flagKey, boolean defaultValue) {
        boolean result = defaultValue;
        try {
            result = userManager.getCurrentUserSharedPrefs().getBoolean(flagKey, defaultValue);
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get boolean flag that exists as another type for key: "
                    + flagKey + " Returning default: " + defaultValue, cce);
        }
        sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(defaultValue));
        return result;
    }

    /**
     * Returns the flag value for the current user. Returns defaultValue when one of the following occurs:
     * 1. Flag is missing
     * 2. The flag is not of a int type.
     * 3. Any other error.
     *
     * @param flagKey
     * @param defaultValue
     * @return
     */
    @Override
    public Integer intVariation(String flagKey, int defaultValue) {
        Integer result = defaultValue;
        try {
            result = (int) userManager.getCurrentUserSharedPrefs().getFloat(flagKey, defaultValue);
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get integer flag that exists as another type for key: "
                    + flagKey + " Returning default: " + defaultValue, cce);
        }
        sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(defaultValue));
        return result;
    }

    /**
     * Returns the flag value for the current user. Returns defaultValue when one of the following occurs:
     * 1. Flag is missing
     * 2. The flag is not of a float type.
     * 3. Any other error.
     *
     * @param flagKey
     * @param defaultValue
     * @return
     */
    @Override
    public Float floatVariation(String flagKey, Float defaultValue) {
        float result = defaultValue;
        try {
            result = userManager.getCurrentUserSharedPrefs().getFloat(flagKey, defaultValue);
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get float flag that exists as another type for key: "
                    + flagKey + " Returning default: " + defaultValue, cce);
        }
        sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(defaultValue));
        return result;
    }

    /**
     * Returns the flag value for the current user. Returns defaultValue when one of the following occurs:
     * 1. Flag is missing
     * 2. The flag is not of a String type.
     * 3. Any other error.
     *
     * @param flagKey
     * @param defaultValue
     * @return
     */
    @Override
    public String stringVariation(String flagKey, String defaultValue) {
        String result = defaultValue;
        try {
            result = userManager.getCurrentUserSharedPrefs().getString(flagKey, defaultValue);
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get string flag that exists as another type for key: "
                    + flagKey + " Returning default: " + defaultValue, cce);
        }
        sendFlagRequestEvent(flagKey, new JsonPrimitive(result), new JsonPrimitive(defaultValue));
        return result;
    }

    /**
     * Returns the flag value for the current user. Returns defaultValue when one of the following occurs:
     * 1. Flag is missing
     * 2. The flag is not valid JSON.
     * 3. Any other error.
     *
     * @param flagKey
     * @param defaultValue
     * @return
     */
    @Override
    public JsonElement jsonVariation(String flagKey, JsonElement defaultValue) {
        JsonElement result = defaultValue;
        try {
            String stringResult = userManager.getCurrentUserSharedPrefs().getString(flagKey, null);
            if (stringResult != null) {
                result = new JsonParser().parse(stringResult);
            }
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get json (string) flag that exists as another type for key: "
                    + flagKey + " Returning default: " + defaultValue, cce);
        }
        sendFlagRequestEvent(flagKey, result, defaultValue);
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
    public boolean isOffline() {
        return config.isOffline();
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

    void stopStreaming() {
        streamProcessor.stop();
    }

    void startStreaming() {
        streamProcessor.start();
    }

    private void sendFlagRequestEvent(String featureKey, JsonElement value, JsonElement defaultValue) {
        sendEvent(new FeatureRequestEvent(featureKey, userManager.getCurrentUser(), value, defaultValue));
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
