package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

public class LDClient implements LDClientInterface, Closeable {
    private static final String TAG = "LaunchDarkly";
    private static LDClient instance = null;
    private final LDConfig config;
    private final SharedPreferences sharedPrefs;
    private LDUser user;
    private EventProcessor eventProcessor;
    private StreamProcessor streamProcessor;


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
        this.user = user;
        this.config = config;

        String sharedPrefsKey = "LaunchDarkly-" + application.getPackageName();
        Log.i(TAG,"Using SharedPreferences key: " + sharedPrefsKey);
        this.sharedPrefs = application.getSharedPreferences(sharedPrefsKey, Context.MODE_PRIVATE);

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

            FeatureFlagUpdater updater = FeatureFlagUpdater.init(config, sharedPrefs, user);
            this.streamProcessor = new StreamProcessor(config, updater);
            streamProcessor.start();
            eventProcessor = new EventProcessor(config);
            identify(user);
        }
    }

    @Override
    public boolean initialized() {
        return false;
    }

    @Override
    public void track(String key, JsonElement data) {
        sendEvent(new CustomEvent(key, user, data));
    }

    @Override
    public void track(String key) {
        sendEvent(new CustomEvent(key, user, null));
    }

    @Override
    public void identify(LDUser user) {
        this.user = user;
        sendEvent(new IdentifyEvent(user));
    }

    @Override
    public Map<String, ?> allFlags() {
        return sharedPrefs.getAll();
    }

    @Override
    public Boolean boolVariation(String featureKey, boolean defaultValue) {
        boolean result = defaultValue;
        try {
            result = sharedPrefs.getBoolean(featureKey, defaultValue);
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
            result = (int) sharedPrefs.getFloat(featureKey, defaultValue);
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
            result = sharedPrefs.getFloat(featureKey, defaultValue);
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
            result = sharedPrefs.getString(featureKey, defaultValue);
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
            String stringResult = sharedPrefs.getString(featureKey, null);
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
        sendEvent(new FeatureRequestEvent(featureKey, user, value, defaultValue));
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
