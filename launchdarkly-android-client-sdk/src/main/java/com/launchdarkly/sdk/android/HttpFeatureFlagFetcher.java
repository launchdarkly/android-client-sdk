package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.json.JsonSerialization;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static com.launchdarkly.sdk.android.LDConfig.JSON;

class HttpFeatureFlagFetcher implements FeatureFetcher {

    private static final int MAX_CACHE_SIZE_BYTES = 500_000;

    private final LDConfig config;
    private final URI pollUri;
    private final HttpProperties httpProperties;
    private final ClientState clientStateProvider;
    private final PlatformState platformState;
    private final OkHttpClient client;
    private final LDLogger logger;

    static HttpFeatureFlagFetcher newInstance(
            @NonNull PlatformState platformState,
            @NonNull LDConfig config,
            @NonNull ClientState clientStateProvider
    ) {
        return new HttpFeatureFlagFetcher(platformState, config, clientStateProvider);
    }

    private HttpFeatureFlagFetcher(
            @NonNull PlatformState platformState,
            @NonNull LDConfig config,
            @NonNull ClientState clientStateProvider
    ) {
        this.config = config;
        this.pollUri = config.serviceEndpoints.getPollingBaseUri();
        this.clientStateProvider = clientStateProvider;
        this.httpProperties = LDUtil.makeHttpProperties(config, clientStateProvider.getMobileKey());
        this.platformState = platformState;
        this.logger = clientStateProvider.getLogger();

        File cacheDir = new File(platformState.getCacheDir(), "com.launchdarkly.http-cache");
        logger.debug("Using cache at: {}", cacheDir.getAbsolutePath());

        client = httpProperties.toHttpClientBuilder()
                // The following client options are currently only used for polling requests; caching is
                // not relevant for streaming or events, and we don't use OkHttp's auto-retry logic for
                // streaming or events because we have our own different retry logic. However, in the
                // the future we may want to share a ConnectionPool across clients. We may also want to
                // create a single HTTP client at init() time and share it across multiple SDK clients,
                // if there are multiple environments; right now a new HTTP client is being created for
                // polling for each environment, even though they all have the same configuration.
                .cache(new Cache(cacheDir, MAX_CACHE_SIZE_BYTES))
                .connectionPool(new ConnectionPool(1, config.getBackgroundPollingIntervalMillis() * 2, TimeUnit.MILLISECONDS))
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public synchronized void fetch(LDContext ldContext, final LDUtil.ResultCallback<String> callback) {
        if (ldContext != null && !clientStateProvider.isForcedOffline() && platformState.isNetworkAvailable()) {

            final Request request;
            try {
                request = config.isUseReport()
                        ? getReportRequest(ldContext)
                        : getDefaultRequest(ldContext);
            } catch (IOException e) {
                LDUtil.logExceptionAtErrorLevel(logger, e, "Unexpected error in constructing request");
                callback.onError(new LDFailure("Exception while fetching flags", e, LDFailure.FailureType.UNKNOWN_ERROR));
                return;
            }

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
                            return;
                        }
                        logger.debug(body);
                        logger.debug("Cache hit count: {} Cache network Count: {}", client.cache().hitCount(), client.cache().networkCount());
                        logger.debug("Cache response: {}", response.cacheResponse());
                        logger.debug("Network response: {}", response.networkResponse());

                        callback.onSuccess(body);
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

    private Request getDefaultRequest(LDContext ldContext) throws IOException {
        // Here we're using java.net.URI and our own URI-building helpers, rather than android.net.Uri
        // and methods like Uri.withAppendedPath, simply to minimize the amount of code that relies on
        // Android-specific APIs so our components are more easily unit-testable.
        URI uri = HttpHelpers.concatenateUriPath(pollUri, StandardEndpoints.POLLING_REQUEST_GET_BASE_PATH);
        uri = HttpHelpers.concatenateUriPath(uri, LDUtil.base64Url(ldContext));
        if (config.isEvaluationReasons()) {
            uri = URI.create(uri.toString() + "?withReasons=true");
        }
        logger.debug("Attempting to fetch Feature flags using uri: {}", uri);
        return new Request.Builder().url(uri.toURL())
                .headers(httpProperties.toHeadersBuilder().build())
                .build();
    }

    private Request getReportRequest(LDContext ldContext) throws IOException {
        URI uri = HttpHelpers.concatenateUriPath(pollUri, StandardEndpoints.POLLING_REQUEST_REPORT_BASE_PATH);
        if (config.isEvaluationReasons()) {
            uri = URI.create(uri.toString() + "?withReasons=true");
        }
        logger.debug("Attempting to report user using uri: {}", uri);
        String contextJson = JsonSerialization.serialize(ldContext);
        RequestBody reportBody = RequestBody.create(contextJson, JSON);

        return new Request.Builder().url(uri.toURL())
                .headers(httpProperties.toHeadersBuilder().build())
                .method("REPORT", reportBody)
                .build();
    }
}
