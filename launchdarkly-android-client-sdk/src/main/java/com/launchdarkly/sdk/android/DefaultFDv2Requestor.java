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
import java.util.List;
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
    private static final String BASIS_PARAM = "basis";
    private static final String FILTER_PARAM = "filter";
    private static final String WITH_REASONS_PARAM = "withReasons";
    private static final String IF_NONE_MATCH_HEADER = "If-None-Match";
    private static final String ETAG_HEADER = "ETag";

    private final OkHttpClient httpClient;
    private final URI pollingUri;
    private final okhttp3.Headers headers;
    private final boolean useReport;
    private final boolean evaluationReasons;
    @Nullable
    private final RequestBody reportBody;
    @Nullable
    private final String payloadFilter;
    private final LDLogger logger;

    private final Object etagLock = new Object();
    /** Last ETag received from the server; guarded by {@link #etagLock}. */
    @Nullable private String cachedEtag;
    /** The request URI that {@link #cachedEtag} corresponds to; guarded by {@link #etagLock}. */
    @Nullable private URI lastRequestUri;

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
        this.reportBody = useReport
                ? RequestBody.create(JsonSerialization.serialize(evaluationContext), JSON)
                : null;

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

            logger.debug("Polling request to: {}", requestUri);

            Request.Builder reqBuilder = new Request.Builder()
                    .url(requestUri.toURL())
                    .headers(headers);

            if (useReport) {
                reqBuilder.method(METHOD_REPORT, reportBody);
            } else {
                synchronized (etagLock) {
                    if (!requestUri.equals(lastRequestUri)) {
                        cachedEtag = null;
                        lastRequestUri = requestUri;
                    }
                    if (cachedEtag != null) {
                        reqBuilder.header(IF_NONE_MATCH_HEADER, cachedEtag);
                    }
                }
                reqBuilder.get();
            }

            httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    future.setException(e);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        handleResponse(response, future);
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
            @NonNull LDAwaitFuture<FDv2PayloadResponse> future) {
        try {
            int code = response.code();

            if (code == 304) {
                logger.debug("FDv2 polling: 304 Not Modified");
                future.set(FDv2PayloadResponse.notModified());
                return;
            }

            if (!response.isSuccessful()) {
                // if we get a 400, odds are that a minifier interfered with serialization related types at compile time.  R8 and
                // ProGuard are both common minifiers that end users customize.
                if (code == 400) {
                    logger.error("Received 400 response when fetching flag values. Please check recommended R8 and/or ProGuard settings");
                } else {
                    logger.warn("Polling request failed with HTTP {}", code);
                }
                future.set(FDv2PayloadResponse.failure(code));
                return;
            }

            if (!useReport) {
                synchronized (etagLock) {
                    cachedEtag = response.header(ETAG_HEADER);
                }
            }

            ResponseBody body = response.body();
            if (body == null) {
                future.setException(new IOException("FDv2 polling response had no body"));
                return;
            }

            String bodyStr = body.string();
            logger.debug("Polling response received");
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
