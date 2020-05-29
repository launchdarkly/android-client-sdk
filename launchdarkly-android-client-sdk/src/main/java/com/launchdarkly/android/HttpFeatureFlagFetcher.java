package com.launchdarkly.android;

import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

import static com.launchdarkly.android.LDConfig.GSON;
import static com.launchdarkly.android.LDUtil.isClientConnected;

class HttpFeatureFlagFetcher implements FeatureFetcher {

    private static final int MAX_CACHE_SIZE_BYTES = 500_000;

    private final LDConfig config;
    private final String environmentName;
    private final Context context;
    private final OkHttpClient client;

    static HttpFeatureFlagFetcher newInstance(Context context, LDConfig config, String environmentName) {
        return new HttpFeatureFlagFetcher(context, config, environmentName);
    }

    private HttpFeatureFlagFetcher(Context context, LDConfig config, String environmentName) {
        this.config = config;
        this.environmentName = environmentName;
        this.context = context;

        File cacheDir = new File(context.getCacheDir(), "com.launchdarkly.http-cache");
        Timber.d("Using cache at: %s", cacheDir.getAbsolutePath());

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .cache(new Cache(cacheDir, MAX_CACHE_SIZE_BYTES))
                .connectionPool(new ConnectionPool(1, config.getBackgroundPollingIntervalMillis() * 2, TimeUnit.MILLISECONDS))
                .retryOnConnectionFailure(true);

        if (Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 22) {
            try {
                builder.sslSocketFactory(new ModernTLSSocketFactory(), TLSUtils.defaultTrustManager());
            } catch (GeneralSecurityException ignored) {
                // TLS is not available, so don't set up the socket factory, swallow the exception
            }
        }

        client = builder.build();
    }

    @Override
    public synchronized void fetch(LDUser user, final LDUtil.ResultCallback<JsonObject> callback) {
        if (user != null && isClientConnected(context, environmentName)) {

            final Request request = config.isUseReport()
                    ? getReportRequest(user)
                    : getDefaultRequest(user);

            Timber.d(request.toString());
            Call call = client.newCall(request);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Timber.e(e, "Exception when fetching flags.");
                    callback.onError(new LDFailure("Exception while fetching flags", e, LDFailure.FailureType.NETWORK_FAILURE));
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull final Response response) {
                    String body = "";
                    try {
                        ResponseBody responseBody = response.body();
                        if (responseBody != null) {
                            body = responseBody.string();
                        }
                        if (!response.isSuccessful()) {
                            if (response.code() == 400) {
                                Timber.e("Received 400 response when fetching flag values. Please check recommended ProGuard settings");
                            }
                            callback.onError(new LDInvalidResponseCodeFailure("Unexpected response when retrieving Feature Flags: " + response + " using url: "
                                    + request.url() + " with body: " + body, response.code(), true));
                        }
                        Timber.d(body);
                        Timber.d("Cache hit count: %s Cache network Count: %s", client.cache().hitCount(), client.cache().networkCount());
                        Timber.d("Cache response: %s", response.cacheResponse());
                        Timber.d("Network response: %s", response.networkResponse());

                        JsonParser parser = new JsonParser();
                        JsonObject jsonObject = parser.parse(body).getAsJsonObject();
                        callback.onSuccess(jsonObject);
                    } catch (Exception e) {
                        Timber.e(e, "Exception when handling response for url: %s with body: %s", request.url(), body);
                        callback.onError(new LDFailure("Exception while handling flag fetch response", e, LDFailure.FailureType.INVALID_RESPONSE_BODY));
                    } finally {
                        if (response != null) {
                            response.close();
                        }
                    }
                }
            });
        }
    }

    private Request getDefaultRequest(LDUser user) {
        String uri = config.getBaseUri() + "/msdk/evalx/users/" + user.getAsUrlSafeBase64();
        if (config.isEvaluationReasons()) {
            uri += "?withReasons=true";
        }
        Timber.d("Attempting to fetch Feature flags using uri: %s", uri);
        return config.getRequestBuilderFor(environmentName) // default GET verb
                .url(uri)
                .build();
    }

    private Request getReportRequest(LDUser user) {
        String reportUri = config.getBaseUri() + "/msdk/evalx/user";
        if (config.isEvaluationReasons()) {
            reportUri += "?withReasons=true";
        }
        Timber.d("Attempting to report user using uri: %s", reportUri);
        String userJson = GSON.toJson(user);
        RequestBody reportBody = RequestBody.create(MediaType.parse("application/json;charset=UTF-8"), userJson);
        return config.getRequestBuilderFor(environmentName)
                .method("REPORT", reportBody) // custom REPORT verb
                .url(reportUri)
                .build();
    }
}
