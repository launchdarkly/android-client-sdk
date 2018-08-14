package com.launchdarkly.android;


import android.content.Context;
import android.os.Build;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.launchdarkly.android.tls.ModernTLSSocketFactory;
import com.launchdarkly.android.tls.TLSUtils;

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
import static com.launchdarkly.android.Util.isInternetConnected;

class HttpFeatureFlagFetcher implements FeatureFlagFetcher {

    private static final int MAX_CACHE_SIZE_BYTES = 500_000;

    private static HttpFeatureFlagFetcher instance;

    private final LDConfig config;
    private final Context context;
    private final OkHttpClient client;

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

        File cacheDir = context.getCacheDir();
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
    public synchronized ListenableFuture<JsonObject> fetch(LDUser user) {
        final SettableFuture<JsonObject> doneFuture = SettableFuture.create();

        if (user != null && !isOffline && isInternetConnected(context)) {

            final Request request = config.isUseReport() ? getReportRequest(user) : getDefaultRequest(user);

            Timber.d(request.toString());
            Call call = client.newCall(request);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Timber.e(e, "Exception when fetching flags.");
                    doneFuture.setException(e);
                }

                @Override
                public void onResponse(Call call, final Response response) throws IOException {
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
                            throw new IOException("Unexpected response when retrieving Feature Flags: " + response + " using url: "
                                    + request.url() + " with body: " + body);
                        }
                        Timber.d(body);
                        Timber.d("Cache hit count: " + client.cache().hitCount() + " Cache network Count: " + client.cache().networkCount());
                        Timber.d("Cache response: %s", response.cacheResponse());
                        Timber.d("Network response: %s", response.networkResponse());

                        JsonParser parser = new JsonParser();
                        JsonObject jsonObject = parser.parse(body).getAsJsonObject();
                        doneFuture.set(jsonObject);
                    } catch (Exception e) {
                        Timber.e(e, "Exception when handling response for url: " + request.url() + " with body: " + body);
                        doneFuture.setException(e);
                    } finally {
                        if (response != null) {
                            response.close();
                        }
                    }
                }
            });
        } else {
            if (user == null) {
                doneFuture.setException(new LaunchDarklyException("Update was attempted without a user"));
            } else {
                doneFuture.setException(new LaunchDarklyException("Update was attempted without an internet connection"));
            }
        }
        return doneFuture;
    }

    private Request getDefaultRequest(LDUser user) {
        String uri = config.getBaseUri() + "/msdk/evalx/users/" + user.getAsUrlSafeBase64();
        Timber.d("Attempting to fetch Feature flags using uri: %s", uri);
        final Request request = config.getRequestBuilder() // default GET verb
                .url(uri)
                .build();
        return request;
    }

    private Request getReportRequest(LDUser user) {
        String reportUri = config.getBaseUri() + "/msdk/evalx/user";
        Timber.d("Attempting to report user using uri: %s", reportUri);
        String userJson = GSON.toJson(user);
        RequestBody reportBody = RequestBody.create(MediaType.parse("application/json;charset=UTF-8"), userJson);
        final Request report = config.getRequestBuilder()
                .method("REPORT", reportBody) // custom REPORT verb
                .url(reportUri)
                .build();
        return report;
    }

    @Override
    public void setOffline() {
        isOffline = true;
    }

    @Override
    public void setOnline() {
        isOffline = false;
    }

}
