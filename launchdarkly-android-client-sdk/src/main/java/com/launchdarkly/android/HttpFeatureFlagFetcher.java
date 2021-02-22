package com.launchdarkly.android;
import com.launchdarkly.sdk.LDUser;

import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
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
import static com.launchdarkly.android.LDConfig.JSON;
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

        LDUtil.setupSocketFactory(builder);

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

                        JsonObject jsonObject = JsonParser.parseString(body).getAsJsonObject();
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
        String uri = config.getPollUri() + "/msdk/evalx/users/" + DefaultUserManager.base64Url(user);
        if (config.isEvaluationReasons()) {
            uri += "?withReasons=true";
        }
        Timber.d("Attempting to fetch Feature flags using uri: %s", uri);
        Request.Builder requestBuilder = config.getRequestBuilderFor(environmentName) // default GET verb
                .url(uri);
        return config.buildRequestWithAdditionalHeaders(requestBuilder);
    }

    private Request getReportRequest(LDUser user) {
        String reportUri = config.getPollUri() + "/msdk/evalx/user";
        if (config.isEvaluationReasons()) {
            reportUri += "?withReasons=true";
        }
        Timber.d("Attempting to report user using uri: %s", reportUri);
        String userJson = GSON.toJson(user);
        RequestBody reportBody = RequestBody.create(userJson, JSON);
        Request.Builder requestBuilder = config.getRequestBuilderFor(environmentName)
                .method("REPORT", reportBody) // custom REPORT verb
                .url(reportUri);
        return config.buildRequestWithAdditionalHeaders(requestBuilder);
    }
}
