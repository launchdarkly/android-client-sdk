package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.json.JsonSerialization;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
 * FDv1 polling synchronizer used as a fallback when the server signals that FDv2 endpoints
 * are unavailable via the {@code x-ld-fd-fallback} response header.
 * <p>
 * Polls the FDv1 mobile evaluation endpoint and converts the response into
 * {@link FDv2SourceResult} objects so it can be used as a drop-in synchronizer within the
 * FDv2 data source pipeline.
 */
final class FDv1PollingSynchronizer implements Synchronizer {

    private final URI pollingUri;
    private final boolean useReport;
    private final boolean evaluationReasons;
    private final okhttp3.Headers headers;
    private final RequestBody reportBody;
    private final OkHttpClient httpClient;
    private final LDLogger logger;

    private final LDAsyncQueue<FDv2SourceResult> resultQueue = new LDAsyncQueue<>();
    private final LDAwaitFuture<FDv2SourceResult> shutdownFuture = new LDAwaitFuture<>();

    private volatile ScheduledFuture<?> scheduledTask;
    private final Object taskLock = new Object();

    /**
     * @param evaluationContext  the context to evaluate flags for
     * @param pollingBaseUri     base URI for the FDv1 polling endpoint
     * @param httpProperties     SDK HTTP configuration
     * @param useReport          true to use HTTP REPORT with context in body
     * @param evaluationReasons  true to request evaluation reasons
     * @param executor           scheduler for recurring poll tasks
     * @param initialDelayMillis delay before the first poll in milliseconds
     * @param pollIntervalMillis delay between the end of one poll and the start of the next
     * @param logger             logger
     */
    FDv1PollingSynchronizer(
            @NonNull LDContext evaluationContext,
            @NonNull URI pollingBaseUri,
            @NonNull HttpProperties httpProperties,
            boolean useReport,
            boolean evaluationReasons,
            @NonNull ScheduledExecutorService executor,
            long initialDelayMillis,
            long pollIntervalMillis,
            @NonNull LDLogger logger) {
        this.useReport = useReport;
        this.evaluationReasons = evaluationReasons;
        this.logger = logger;
        this.headers = httpProperties.toHeadersBuilder().build();

        URI basePath = HttpHelpers.concatenateUriPath(pollingBaseUri,
                useReport
                        ? StandardEndpoints.POLLING_REQUEST_REPORT_BASE_PATH
                        : StandardEndpoints.POLLING_REQUEST_GET_BASE_PATH);
        this.pollingUri = useReport
                ? basePath
                : HttpHelpers.concatenateUriPath(basePath, LDUtil.urlSafeBase64(evaluationContext));
        this.reportBody = useReport
                ? RequestBody.create(JsonSerialization.serialize(evaluationContext), JSON)
                : null;

        this.httpClient = httpProperties.toHttpClientBuilder()
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .retryOnConnectionFailure(true)
                .build();

        synchronized (taskLock) {
            scheduledTask = executor.scheduleWithFixedDelay(
                    this::pollAndEnqueue,
                    initialDelayMillis,
                    pollIntervalMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void pollAndEnqueue() {
        try {
            FDv2SourceResult result = doPoll();

            if (result.getResultType() == com.launchdarkly.sdk.fdv2.SourceResultType.STATUS) {
                FDv2SourceResult.Status status = result.getStatus();
                if (status != null && status.getState() == com.launchdarkly.sdk.fdv2.SourceSignal.TERMINAL_ERROR) {
                    synchronized (taskLock) {
                        if (scheduledTask != null) {
                            scheduledTask.cancel(false);
                            scheduledTask = null;
                        }
                    }
                    closeHttpClient();
                    shutdownFuture.set(result);
                    return;
                }
            }

            resultQueue.put(result);
        } catch (RuntimeException e) {
            LDUtil.logExceptionAtErrorLevel(logger, e, "Unexpected exception in FDv1 polling synchronizer task");
            resultQueue.put(FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(e), false));
        }
    }

    private FDv2SourceResult doPoll() {
        LDAwaitFuture<FDv2SourceResult> pollFuture = new LDAwaitFuture<>();

        try {
            URI requestUri = pollingUri;
            if (evaluationReasons) {
                requestUri = HttpHelpers.addQueryParam(requestUri, "withReasons", "true");
            }

            logger.debug("FDv1 fallback polling request to: {}", requestUri);

            Request.Builder reqBuilder = new Request.Builder()
                    .url(requestUri.toURL())
                    .headers(headers);

            if (useReport) {
                reqBuilder.method("REPORT", reportBody);
            } else {
                reqBuilder.get();
            }

            httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    pollFuture.setException(e);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        handleResponse(response, pollFuture);
                    } finally {
                        response.close();
                    }
                }
            });

            return pollFuture.get();
        } catch (InterruptedException e) {
            return FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(e), false);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException) {
                LDUtil.logExceptionAtErrorLevel(logger, cause, "FDv1 fallback polling failed with network error");
            } else {
                LDUtil.logExceptionAtErrorLevel(logger, cause, "FDv1 fallback polling failed");
            }
            return FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(cause), false);
        }
    }

    private void handleResponse(@NonNull Response response, @NonNull LDAwaitFuture<FDv2SourceResult> future) {
        try {
            int code = response.code();

            if (!response.isSuccessful()) {
                if (code == 400) {
                    logger.error("Received 400 response when fetching flag values. Please check recommended R8 and/or ProGuard settings");
                }
                boolean recoverable = LDUtil.isHttpErrorRecoverable(code);
                logger.warn("FDv1 fallback polling failed with HTTP {}", code);
                LDFailure failure = new LDInvalidResponseCodeFailure(
                        "FDv1 fallback polling request failed", null, code, recoverable);
                if (!recoverable) {
                    future.set(FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(failure), false));
                } else {
                    future.set(FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), false));
                }
                return;
            }

            ResponseBody body = response.body();
            if (body == null) {
                future.setException(new IOException("FDv1 fallback polling response had no body"));
                return;
            }

            String bodyStr = body.string();
            logger.debug("FDv1 fallback polling response received");

            EnvironmentData envData = EnvironmentData.fromJson(bodyStr);
            Map<String, Flag> flags = envData.getAll();

            ChangeSet<Map<String, Flag>> changeSet = new ChangeSet<>(
                    ChangeSetType.Full,
                    Selector.EMPTY,
                    flags,
                    null,
                    true);

            future.set(FDv2SourceResult.changeSet(changeSet, false));

        } catch (SerializationException e) {
            LDUtil.logExceptionAtErrorLevel(logger, e, "FDv1 fallback polling failed to parse response");
            LDFailure failure = new LDFailure(
                    "FDv1 fallback: invalid JSON response", e, LDFailure.FailureType.INVALID_RESPONSE_BODY);
            future.set(FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), false));
        } catch (Exception e) {
            future.setException(e);
        }
    }

    @Override
    @NonNull
    public Future<FDv2SourceResult> next() {
        return LDFutures.anyOf(shutdownFuture, resultQueue.take());
    }

    @Override
    public void close() {
        synchronized (taskLock) {
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = null;
            }
        }
        shutdownFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
        closeHttpClient();
    }

    private void closeHttpClient() {
        HttpProperties.shutdownHttpClient(httpClient);
    }
}
