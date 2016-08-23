package com.launchdarkly.android;


import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.Future;

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
    private final UserManager userManager;

    static FeatureFlagUpdater init(LDConfig config, UserManager userManager) {
        instance = new FeatureFlagUpdater(config, userManager);
        return instance;
    }

    static FeatureFlagUpdater getInstance() {
        return instance;
    }

    private FeatureFlagUpdater(LDConfig config, UserManager userManager) {
        this.config = config;
        this.userManager = userManager;
    }

    Future<Void> update() {
        final Type mapType = new TypeToken<Map<String, JsonElement>>() {
        }.getType();
        final VeryBasicFuture doneFuture = new VeryBasicFuture();
        //TODO: caching stuff
        OkHttpClient client = new OkHttpClient();
        String uri = config.getBaseUri() + "/msdk/eval/users/" + userManager.getCurrentUser().getAsUrlSafeBase64();
        final Request request = config.getRequestBuilder()
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
                        throw new IOException("Unexpected response when retrieving Feature Flags:  " + response + " using url: " + request.url());
                    }
                    String body = response.body().string();
                    Log.i(TAG, body);
                    Map<String, JsonElement> resultMap = GSON.fromJson(body, mapType);
                    userManager.saveFlagSettingsForUser(resultMap);
                    doneFuture.completed(null);
                } catch (Exception e) {
                    Log.e(TAG, "Exception when handling response for url: " + request.url(), e);
                    doneFuture.failed(e);
                }
                finally {
                    if (response != null) {
                        response.close();
                    }
                }
            }
        });
        return doneFuture;
    }
}
