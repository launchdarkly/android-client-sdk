package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.json.JsonSerialization;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static com.launchdarkly.sdk.android.LDConfig.JSON;

/**
 * Default OkHttp-based implementation of {@link FDv2Requestor}.
 * <p>
 * Builds GET or REPORT requests to the FDv2 polling endpoint. If the current selector is
 * non-empty, its state is sent as the {@code basis} query parameter. ETag tracking is used
 * to detect 304 Not Modified responses; when the server returns 304 the application layer
 * treats it as {@code ChangeSetType.None} (no flags changed), so no OkHttp disk cache is
 * needed or used.
 * <p>
 * The OkHttpClient is closed by {@link #close()}.
 */
final class DefaultFDv2Requestor implements FDv2Requestor {
    private static final String METHOD_REPORT = "REPORT";
    private static final int MAX_ETAG_CACHE_SIZE = 10;
    private static final String BASIS_PARAM = "basis";
    private static final String FILTER_PARAM = "filter";
    private static final String WITH_REASONS_PARAM = "withReasons";
    private static final String IF_NONE_MATCH_HEADER = "If-None-Match";
    private static final String ETAG_HEADER = "ETag";

    private final OkHttpClient httpClient;
    private final URI pollingUri;
    private final okhttp3.Headers headers;
    private final LDContext evaluationContext;
    private final boolean useReport;
    private final boolean evaluationReasons;
    @Nullable
    private final String payloadFilter;
    private final LDLogger logger;

    /** Per-URI ETag cache; guarded by its own lock. */
    private final Map<URI, String> etags = new HashMap<>();

    /**
     * @param evaluationContext  the context to evaluate flags for
     * @param baseUri            polling base URI from service endpoints
     * @param getRequestPath     path for GET requests (context appended as a path segment)
     * @param reportRequestPath  path for REPORT requests (context sent in the request body)
     * @param httpProperties     SDK HTTP configuration (timeouts, proxy, TLS, user-agent, etc.)
     * @param useReport          if true, send context in the request body via REPORT; otherwise
     *                           append the base64-encoded context to the GET path
     * @param evaluationReasons  if true, append {@code withReasons=true} to the query string
     * @param payloadFilter      optional payload filter key; sent as {@code filter} query param
     * @param logger             logger
     */
    DefaultFDv2Requestor(
            @NonNull LDContext evaluationContext,
            @NonNull URI baseUri,
            @NonNull String getRequestPath,
            @NonNull String reportRequestPath,
            @NonNull HttpProperties httpProperties,
            boolean useReport,
            boolean evaluationReasons,
            @Nullable String payloadFilter,
            @NonNull LDLogger logger) {
        this.evaluationContext = evaluationContext;
        this.useReport = useReport;
        this.evaluationReasons = evaluationReasons;
        this.payloadFilter = payloadFilter;
        this.logger = logger;
        this.headers = httpProperties.toHeadersBuilder().build();

        // Precompute the base polling URI for the chosen request method. For GET, the
        // base64-encoded context is a fixed path segment (context never changes after
        // construction). For REPORT, the context goes in the request body so no path
        // segment is needed.
        URI basePollingUri = HttpHelpers.concatenateUriPath(baseUri,
                useReport ? reportRequestPath : getRequestPath);
        this.pollingUri = useReport
                ? basePollingUri
                : HttpHelpers.concatenateUriPath(basePollingUri, LDUtil.urlSafeBase64(evaluationContext));

        this.httpClient = httpProperties.toHttpClientBuilder()
                // New connection per request: keeping an idle connection alive causes
                // unwanted background network wakeups on Android when the connection expires.
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    @NonNull
    public Future<FDv2PayloadResponse> poll(@NonNull Selector selector) {
        LDAwaitFuture<FDv2PayloadResponse> future = new LDAwaitFuture<>();

        try {
            URI requestUri = pollingUri;

            if (!selector.isEmpty()) {
                requestUri = HttpHelpers.addQueryParam(requestUri, BASIS_PARAM, selector.getState());
            }

            if (payloadFilter != null && !payloadFilter.isEmpty()) {
                requestUri = HttpHelpers.addQueryParam(requestUri, FILTER_PARAM, payloadFilter);
            }

            if (evaluationReasons) {
                requestUri = HttpHelpers.addQueryParam(requestUri, WITH_REASONS_PARAM, "true");
            }

            logger.debug("FDv2 polling request to: {}", requestUri);

            Request.Builder reqBuilder = new Request.Builder()
                    .url(requestUri.toURL())
                    .headers(headers);

            if (useReport) {
                String contextJson = JsonSerialization.serialize(evaluationContext);
                reqBuilder.method(METHOD_REPORT, RequestBody.create(contextJson, JSON));
            } else {
                synchronized (etags) {
                    String etag = etags.get(requestUri);
                    if (etag != null) {
                        reqBuilder.header(IF_NONE_MATCH_HEADER, etag);
                    }
                }
                reqBuilder.get();
            }

            final URI finalRequestUri = requestUri;
            httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    future.setException(e);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        handleResponse(response, finalRequestUri, future);
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (Exception e) {
            future.setException(e);
        }

        return future;
    }

    private void handleResponse(
            @NonNull Response response,
            @NonNull URI requestUri,
            @NonNull LDAwaitFuture<FDv2PayloadResponse> future) {
        try {
            int code = response.code();

            if (code == 304) {
                logger.debug("FDv2 polling: 304 Not Modified");
                future.set(FDv2PayloadResponse.notModified());
                return;
            }

            if (!response.isSuccessful()) {
                if (code == 400) {
                    logger.error("Received 400 response when fetching FDv2 flag values. " +
                            "Please check recommended ProGuard settings");
                } else {
                    logger.warn("FDv2 polling request failed with HTTP {}", code);
                }
                future.set(FDv2PayloadResponse.failure(code));
                return;
            }

            // Update ETag cache. On overflow, flush all stale entries before inserting —
            // only the current URI's ETag is useful anyway, so losing old entries is harmless.
            if (!useReport) {
                String newEtag = response.header(ETAG_HEADER);
                synchronized (etags) {
                    if (newEtag != null) {
                        // Here we impose space limit on etags.  We could track oldest, but that
                        // is more complicated than it is worth.
                        if (etags.size() >= MAX_ETAG_CACHE_SIZE) {
                            etags.clear();
                        }
                        etags.put(requestUri, newEtag);
                    } else {
                        etags.remove(requestUri);
                    }
                }
            }

            ResponseBody body = response.body();
            if (body == null) {
                future.setException(new IOException("FDv2 polling response had no body"));
                return;
            }

            String bodyStr = body.string();
            logger.debug("FDv2 polling response received");
            List<FDv2Event> events = FDv2Event.parseEventsArray(bodyStr);
            future.set(FDv2PayloadResponse.success(events, code));

        } catch (Exception e) {
            future.setException(e);
        }
    }

    @Override
    public void close() {
        HttpProperties.shutdownHttpClient(httpClient);
    }
}
