package com.launchdarkly.android;


import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.launchdarkly.android.LDConfig.GSON;

class FeatureFlagUpdater {
    private static final String TAG = "LDFeatureFlagUpdater";
    private static FeatureFlagUpdater instance;

    private final LDConfig config;
    private final SharedPreferences sharedPrefs;

    private LDUser user;


    static FeatureFlagUpdater init(LDConfig config, SharedPreferences sharedPrefs, LDUser user) {
        instance = new FeatureFlagUpdater(config, sharedPrefs, user);
        return instance;
    }

    static FeatureFlagUpdater getInstance() {
        return instance;
    }

    private FeatureFlagUpdater(LDConfig config, SharedPreferences sharedPrefs, LDUser user) {
        this.config = config;
        this.sharedPrefs = sharedPrefs;
        this.user = user;
    }

    void update() {
        final Type mapType = new TypeToken<Map<String, JsonElement>>() {
        }.getType();
        //TODO: caching stuff
        OkHttpClient client = new OkHttpClient();
        String uri = config.getBaseUri() + "/msdk/eval/users/" + user.getAsUrlSafeBase64();
        Request request = config.getRequestBuilder()
                .url(uri)
                .build();

        Log.d(TAG, request.toString());

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Exception when updating flags.", e);
            }

            @Override
            public void onResponse(Call call, final Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        throw new IOException("Unexpected response when retrieving Feature Flags! " + response);
                    }
                    String body = response.body().string();
                    Log.i(TAG, body);
                    Map<String, JsonElement> resultMap = GSON.fromJson(body, mapType);
                    SharedPreferences.Editor editor = sharedPrefs.edit();
                    editor.clear();
                    for (Map.Entry<String, JsonElement> entry : resultMap.entrySet()) {
                        JsonElement v = entry.getValue();
                        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                            editor.putBoolean(entry.getKey(), v.getAsBoolean());
                        } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                            editor.putFloat(entry.getKey(), v.getAsFloat());
                        } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                            editor.putString(entry.getKey(), v.getAsString());
                        }
                    }
                    editor.apply();
                    logAllFlags();
                }
                finally {
                    if (response != null) {
                        response.close();
                    }
                }
            }
        });
    }


    private void logAllFlags() {
        Map<String, ?> all = sharedPrefs.getAll();
        if (all.size() == 0) {
            Log.i(TAG, "found zero saved feature flags");
        } else {
            Log.i(TAG, "Found feature flags:");
            for (Map.Entry<String, ?> kv : all.entrySet()) {
                Log.i(TAG, "Key: " + kv.getKey() + " value: " + kv.getValue());
            }
        }
    }
}
