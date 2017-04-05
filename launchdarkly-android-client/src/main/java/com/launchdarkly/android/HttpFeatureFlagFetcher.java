package com.launchdarkly.android;


import android.content.Context;
import android.util.Log;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.launchdarkly.android.Util.isInternetConnected;

class HttpFeatureFlagFetcher implements FeatureFlagFetcher {
    private static final String TAG = "LDFeatureFlagFetcher";
    private static final int MAX_CACHE_SIZE_BYTES = 500_000;
    private static HttpFeatureFlagFetcher instance;

    private final LDConfig config;
    private final Context context;
    private final Cache cache;

    private volatile boolean isOffline = false;

    static HttpFeatureFlagFetcher init(Context context, LDConfig config) {
        instance = new HttpFeatureFlagFetcher(context, config);
        return instance;
    }

    static HttpFeatureFlagFetcher get() {
        return instance;
    }

    private HttpFeatureFlagFetcher(Context context, LDConfig config) {
        this.config = config;
        this.context = context;
        this.isOffline = config.isOffline();

        File cacheDir = context.getDir("launchdarkly_api_cache", Context.MODE_PRIVATE);
        deleteRecursive(cacheDir);
        Log.d(TAG, "Using cache at: " + cacheDir.getAbsolutePath());

        cache = new Cache(cacheDir, MAX_CACHE_SIZE_BYTES);
    }

    @Override
    public synchronized ListenableFuture<JsonObject> fetch(LDUser user) {
        final SettableFuture<JsonObject> doneFuture = SettableFuture.create();

        if (!isOffline && isInternetConnected(context)) {
            final OkHttpClient client = new OkHttpClient.Builder()
                    .cache(cache)
                    .retryOnConnectionFailure(true)
                    .build();

            String uri = config.getBaseUri() + "/msdk/eval/users/" + user.getAsUrlSafeBase64();
            Log.d(TAG, "Attempting to fetch Feature flags using uri: " + uri);
            final Request request = config.getRequestBuilder()
                    .url(uri)
                    .build();

            Log.d(TAG, request.toString());
            Call call = client.newCall(request);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Exception when fetching flags.", e);
                    doneFuture.setException(e);
                }

                @Override
                public void onResponse(Call call, final Response response) throws IOException {
                    String body = "";
                    try {
                        body = response.body().string();
                        if (!response.isSuccessful()) {
                            if (response.code() == 400) {
                                Log.e(TAG, "Received 400 response when fetching flag values. Please check recommended ProGuard settings");
                            }
                            throw new IOException("Unexpected response when retrieving Feature Flags: " + response + " using url: "
                                    + request.url() + " with body: " + body);
                        }
                        Log.d(TAG, body);
                        Log.d(TAG, "Cache hit count: " + client.cache().hitCount() + " Cache network Count: " + client.cache().networkCount());
                        Log.d(TAG, "Cache response: " + response.cacheResponse());
                        Log.d(TAG, "Network response: " + response.networkResponse());

                        JsonParser parser = new JsonParser();
                        JsonObject jsonObject = parser.parse(body).getAsJsonObject();
                        doneFuture.set(jsonObject);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception when handling response for url: " + request.url() + " with body: " + body, e);
                        doneFuture.setException(e);
                    } finally {
                        if (response != null) {
                            response.close();
                        }
                    }
                }
            });
        } else {
            doneFuture.setException(new LaunchDarklyException("Update was attempted without an internet connection"));
        }
        return doneFuture;
    }

    @Override
    public void setOffline() {
        isOffline = true;
    }

    @Override
    public void setOnline() {
        isOffline = false;
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }
}
