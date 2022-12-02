package com.launchdarkly.sdk.android;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static com.launchdarkly.sdk.android.LDConfig.GSON;
import static com.launchdarkly.sdk.android.LDConfig.JSON;
import static com.launchdarkly.sdk.android.LDUtil.isClientConnected;

class HttpFeatureFlagFetcher implements FeatureFetcher {

    private static final int MAX_CACHE_SIZE_BYTES = 500_000;

    private final LDConfig config;
    private final HttpConfiguration httpConfig;
    private final Uri pollUri;
    private final String environmentName;
    private final Context context;
    private final OkHttpClient client;
    private final LDLogger logger;

    HttpFeatureFlagFetcher(
            Context context,
            LDConfig config,
            HttpConfiguration httpConfig,
            String environmentName,
            LDLogger logger
    ) {
        this.config = config;
        this.httpConfig = httpConfig;
        this.environmentName = environmentName;
        this.context = context;
        this.logger = logger;

        URI pollUri = StandardEndpoints.selectBaseUri(config.serviceEndpoints.getPollingBaseUri(),
                StandardEndpoints.DEFAULT_POLLING_BASE_URI, "polling", logger);
        this.pollUri = Uri.parse(pollUri.toString());

        File cacheDir = new File(context.getCacheDir(), "com.launchdarkly.http-cache");
        logger.debug("Using cache at: {}", cacheDir.getAbsolutePath());

        client = new OkHttpClient.Builder()
                .cache(new Cache(cacheDir, MAX_CACHE_SIZE_BYTES))
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public synchronized void fetch(LDUser user, final LDUtil.ResultCallback<JsonObject> callback) {
        if (user != null && isClientConnected(context, environmentName)) {

            final Request request = httpConfig.isUseReport()
                    ? getReportRequest(user)
                    : getDefaultRequest(user);

            logger.debug(request.toString());
            Call call = client.newCall(request);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    LDUtil.logExceptionAtErrorLevel(logger, e, "Exception when fetching flags");
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
                                logger.error("Received 400 response when fetching flag values. Please check recommended ProGuard settings");
                            }
                            callback.onError(new LDInvalidResponseCodeFailure("Unexpected response when retrieving Feature Flags: " + response + " using url: "
                                    + request.url() + " with body: " + body, response.code(), true));
                        }
                        logger.debug(body);
                        logger.debug("Cache hit count: {} Cache network Count: {}", client.cache().hitCount(), client.cache().networkCount());
                        logger.debug("Cache response: {}", response.cacheResponse());
                        logger.debug("Network response: {}", response.networkResponse());

                        JsonObject jsonObject = JsonParser.parseString(body).getAsJsonObject();
                        callback.onSuccess(jsonObject);
                    } catch (Exception e) {
                        LDUtil.logExceptionAtErrorLevel(logger, e,
                                "Exception when handling response for url: {} with body: {}", request.url(), body);
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
        String uri = Uri.withAppendedPath(pollUri, "msdk/evalx/users/").toString() +
                DefaultUserManager.base64Url(user);
        if (config.isEvaluationReasons()) {
            uri += "?withReasons=true";
        }
        logger.debug("Attempting to fetch Feature flags using uri: {}", uri);
        return new Request.Builder().url(uri)
                .headers(LDUtil.makeRequestHeaders(httpConfig, null))
                .build();
    }

    private Request getReportRequest(LDUser user) {
        String reportUri = Uri.withAppendedPath(pollUri, "msdk/evalx/user").toString();
        if (config.isEvaluationReasons()) {
            reportUri += "?withReasons=true";
        }
        logger.debug("Attempting to report user using uri: {}", reportUri);
        String userJson = GSON.toJson(user);
        RequestBody reportBody = RequestBody.create(userJson, JSON);

        return new Request.Builder().url(reportUri)
                .headers(LDUtil.makeRequestHeaders(httpConfig, null))
                .method("REPORT", reportBody)
                .build();
    }
}
