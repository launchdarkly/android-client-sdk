package com.launchdarkly.android;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Future;

public class LDClient implements LDClientInterface, Closeable {
    private static final String TAG = "LaunchDarkly";
    private static LDClient instance = null;
    private static final int BACKGROUND_INTERVAL_MS = 10000;
    private final LDConfig config;
    private final UserManager userManager;

    private EventProcessor eventProcessor;
    private StreamProcessor streamProcessor;
    private FeatureFlagUpdater updater;

    private AlarmManager alarmMgr;
    private PendingIntent alarmIntent;

    public static LDClient init(Application application, LDConfig config, LDUser user) {
        if (instance != null) {
            Log.w(TAG, "LDClient.init() was called more than once! returning existing instance.");
            return instance;
        }
        instance = new LDClient(application, config, user);
        return instance;
    }

    public static LDClient get() throws Exception {
        if (instance == null) {
            Log.e(TAG, "LDClient.get() was called before init()!");
            throw new Exception("LDClient.get() was called before init()!");
        }
        return instance;
    }

    private LDClient(Application application, LDConfig config, LDUser user) {
        Log.i(TAG, "Starting LaunchDarkly client");
        this.config = config;
        this.userManager = new UserManager(application, user);
        userManager.setCurrentUser(user);

        if (!isOffline()) {
            Foreground foreground = Foreground.get(application);
            Foreground.Listener foregroundListener = new Foreground.Listener() {
                @Override
                public void onBecameForeground() {
                    streamProcessor.start();
                }

                @Override
                public void onBecameBackground() {
                    streamProcessor.stop();
                }
            };
            foreground.addListener(foregroundListener);

            this.updater = FeatureFlagUpdater.init(application, config, userManager);
            this.streamProcessor = new StreamProcessor(config, updater);
            streamProcessor.start();
            eventProcessor = new EventProcessor(config);
            sendEvent(new IdentifyEvent(user));

            Intent intent = new Intent(application, BackgroundUpdater.class);

            alarmIntent = PendingIntent.getBroadcast(application, 0, intent, 0);

            alarmMgr = (AlarmManager)application.getSystemService(Context.ALARM_SERVICE);

            alarmMgr.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + BACKGROUND_INTERVAL_MS,
                    BACKGROUND_INTERVAL_MS,
                    alarmIntent);
        }
    }

    @Override
    public boolean initialized() {
        return false;
    }

    @Override
    public void track(String key, JsonElement data) {
        sendEvent(new CustomEvent(key, userManager.getCurrentUser(), data));
    }

    @Override
    public void track(String key) {
        sendEvent(new CustomEvent(key, userManager.getCurrentUser(), null));
    }

    /**
     * Sets the current user, retrieves flags for that user, then sends an Identify Event to LaunchDarkly.
     * The 5 most recent users' flag settings are kept locally.
     * @param user
     * @return Future whose success indicates this user's flag settings have been stored locally and are ready for evaluation.
     */
    @Override
    public synchronized Future<Void> identify(LDUser user) {
        userManager.setCurrentUser(user);
        Future<Void> doneFuture = updater.update();
        sendEvent(new IdentifyEvent(user));
        return doneFuture;
    }

    @Override
    public Map<String, ?> allFlags() {
        return userManager.getCurrentUserSharedPrefs().getAll();
    }

    @Override
    public Boolean boolVariation(String featureKey, boolean defaultValue) {
        boolean result = defaultValue;
        try {
            result = userManager.getCurrentUserSharedPrefs().getBoolean(featureKey, defaultValue);
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get boolean flag that exists as another type for key: "
                    + featureKey + " Returning default: " + defaultValue, cce);
        }
        sendFlagRequestEvent(featureKey, new JsonPrimitive(result), new JsonPrimitive(defaultValue));
        return result;
    }

    @Override
    public Integer intVariation(String featureKey, int defaultValue) {
        Integer result = defaultValue;
        try {
            result = (int) userManager.getCurrentUserSharedPrefs().getFloat(featureKey, defaultValue);
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get integer flag that exists as another type for key: "
                    + featureKey + " Returning default: " + defaultValue, cce);
        }
        sendFlagRequestEvent(featureKey, new JsonPrimitive(result), new JsonPrimitive(defaultValue));
        return result;
    }

    @Override
    public Float floatVariation(String featureKey, Float defaultValue) {
        float result = defaultValue;
        try {
            result = userManager.getCurrentUserSharedPrefs().getFloat(featureKey, defaultValue);
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get float flag that exists as another type for key: "
                    + featureKey + " Returning default: " + defaultValue, cce);
        }
        sendFlagRequestEvent(featureKey, new JsonPrimitive(result), new JsonPrimitive(defaultValue));
        return result;
    }

    @Override
    public String stringVariation(String featureKey, String defaultValue) {
        String result = defaultValue;
        try {
            result = userManager.getCurrentUserSharedPrefs().getString(featureKey, defaultValue);
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get string flag that exists as another type for key: "
                    + featureKey + " Returning default: " + defaultValue, cce);
        }
        sendFlagRequestEvent(featureKey, new JsonPrimitive(result), new JsonPrimitive(defaultValue));
        return result;
    }

    @Override
    public JsonElement jsonVariation(String featureKey, JsonElement defaultValue) {
        JsonElement result = defaultValue;
        try {
            String stringResult = userManager.getCurrentUserSharedPrefs().getString(featureKey, null);
            if (stringResult != null) {
                result = new JsonParser().parse(stringResult);
            }
        } catch (ClassCastException cce) {
            Log.e(TAG, "Attempted to get json (string) flag that exists as another type for key: "
                    + featureKey + " Returning default: " + defaultValue, cce);
        }
        sendFlagRequestEvent(featureKey, result, defaultValue);
        return result;
    }

    @Override
    public void close() throws IOException {
        streamProcessor.close();
        eventProcessor.close();
    }

    @Override
    public void flush() {
        eventProcessor.flush();
    }

    @Override
    public boolean isOffline() {
        return config.isOffline();
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
