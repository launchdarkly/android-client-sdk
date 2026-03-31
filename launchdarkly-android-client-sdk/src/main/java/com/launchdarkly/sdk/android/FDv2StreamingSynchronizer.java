package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.launchdarkly.eventsource.ConnectStrategy;
import com.launchdarkly.eventsource.ErrorStrategy;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.FaultEvent;
import com.launchdarkly.eventsource.HttpConnectStrategy;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.RetryDelayStrategy;
import com.launchdarkly.eventsource.StreamClosedByCallerException;
import com.launchdarkly.eventsource.StreamEvent;
import com.launchdarkly.eventsource.ResponseHeaders;
import com.launchdarkly.eventsource.StreamException;
import com.launchdarkly.eventsource.StreamHttpErrorException;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.internal.GsonHelpers;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ProtocolHandler;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.json.JsonSerialization;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.RequestBody;

import static com.launchdarkly.sdk.android.LDConfig.JSON;

/**
 * FDv2 streaming synchronizer for the Android client SDK.
 * <p>
 * Maintains a long-lived SSE connection using the {@link EventSource} API.
 * <p>
 * If an optional {@link FDv2Requestor} is supplied, {@code ping} SSE events are handled by
 * issuing a poll request. If no requestor is supplied, {@code ping} events are ignored.
 * <p>
 */
final class FDv2StreamingSynchronizer implements Synchronizer {
    private static final String METHOD_REPORT = "REPORT";
    private static final String PING = "ping";
    private static final long READ_TIMEOUT_MS = 300_000; // 5 minutes
    private static final long MAX_RECONNECT_TIME_MS = 300_000; // 5 minutes

    private final HttpProperties httpProperties;
    private final URI streamBaseUri;
    private final String streamRequestPath;
    private final boolean useReport;
    private final LDContext evaluationContext;
    private final SelectorSource selectorSource;
    @Nullable
    private final FDv2Requestor requestor;
    private final boolean evaluationReasons;
    private final int initialReconnectDelayMillis;
    @Nullable
    private final DiagnosticStore diagnosticStore;
    private final LDLogger logger;
    private final Executor executor;

    private final LDAsyncQueue<FDv2SourceResult> resultQueue = new LDAsyncQueue<>();
    private final LDAwaitFuture<FDv2SourceResult> shutdownFuture = new LDAwaitFuture<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final FDv2ProtocolHandler protocolHandler = new FDv2ProtocolHandler();

    // closeLock guards: closed and the eventSource assignment in startStream.
    private final Object closeLock = new Object();
    private boolean closed = false;
    private volatile EventSource eventSource;
    private volatile long streamStarted = 0;

    /**
     * @param evaluationContext            the context to evaluate flags for
     * @param selectorSource               source of the current selector, sent as the request basis
     * @param streamBaseUri                base URI for the stream endpoint
     * @param streamRequestPath            path appended to the base URI for the stream request
     * @param requestor                    optional requestor for handling ping events via poll; may be null
     * @param initialReconnectDelayMillis  delay before reconnecting after an error, in milliseconds
     * @param evaluationReasons           true to request evaluation reasons in the stream
     * @param useReport                    true to use HTTP REPORT for the request body
     * @param httpProperties               HTTP configuration for the stream request
     * @param executor                     executor used to run the streaming loop on a background
     *                                     thread; should use background-priority threads
     * @param logger                       logger
     * @param diagnosticStore              optional store for stream diagnostics; may be null
     */
    FDv2StreamingSynchronizer(
            @NonNull LDContext evaluationContext,
            @NonNull SelectorSource selectorSource,
            @NonNull URI streamBaseUri,
            @NonNull String streamRequestPath,
            @Nullable FDv2Requestor requestor,
            int initialReconnectDelayMillis,
            boolean evaluationReasons,
            boolean useReport,
            @NonNull HttpProperties httpProperties,
            @NonNull Executor executor,
            @NonNull LDLogger logger,
            @Nullable DiagnosticStore diagnosticStore
    ) {
        this.evaluationContext = evaluationContext;
        this.selectorSource = selectorSource;
        this.streamBaseUri = streamBaseUri;
        this.streamRequestPath = streamRequestPath;
        this.requestor = requestor;
        this.initialReconnectDelayMillis = initialReconnectDelayMillis;
        this.evaluationReasons = evaluationReasons;
        this.useReport = useReport;
        this.httpProperties = httpProperties;
        this.executor = executor;
        this.logger = logger;
        this.diagnosticStore = diagnosticStore;
    }

    @Override
    public Future<FDv2SourceResult> next() {
        boolean shouldStart;
        synchronized (closeLock) {
            shouldStart = !closed && !started.getAndSet(true);
        }
        if (shouldStart) {
            startStream();
        }
        return LDFutures.anyOf(shutdownFuture, resultQueue.take());
    }

    @Override
    public void close() {
        EventSource esToClose;
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            closed = true;
            esToClose = eventSource;
            eventSource = null;
        }
        if (esToClose != null) {
            esToClose.close();
        }
        if (requestor != null) {
            try {
                requestor.close();
            } catch (IOException ignored) {
            }
        }
        shutdownFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown()));
    }

    private void startStream() {
        HttpConnectStrategy connectStrategy = ConnectStrategy.http(getStreamUri())
                .clientBuilderActions(clientBuilder -> {
                    httpProperties.applyToHttpClientBuilder(clientBuilder);
                    clientBuilder.readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                })
                .requestTransformer(request -> {
                    Selector selector = selectorSource.getSelector();
                    URI currentUri = request.url().uri();
                    URI updatedUri = currentUri;

                    if (!selector.isEmpty()) {
                        updatedUri = HttpHelpers.addQueryParam(updatedUri, "basis", selector.getState());
                    }

                    okhttp3.Request.Builder reqBuilder = request.newBuilder()
                            .headers(request.headers().newBuilder()
                                    .addAll(httpProperties.toHeadersBuilder().build())
                                    .build());

                    if (!updatedUri.equals(currentUri)) {
                        reqBuilder.url(updatedUri.toString());
                    }
                    return reqBuilder.build();
                });

        if (useReport) {
            connectStrategy = connectStrategy.methodAndBody(
                    METHOD_REPORT,
                    RequestBody.create(JsonSerialization.serialize(evaluationContext), JSON));
        }

        EventSource es = new EventSource.Builder(connectStrategy)
                .retryDelay(initialReconnectDelayMillis, TimeUnit.MILLISECONDS)
                .retryDelayStrategy(RetryDelayStrategy.defaultStrategy()
                        .maxDelay(MAX_RECONNECT_TIME_MS, TimeUnit.MILLISECONDS))
                .errorStrategy(ErrorStrategy.alwaysContinue())
                .build();

        synchronized (closeLock) {
            if (closed) {
                es.close();
                return;
            }
            eventSource = es;
        }

        executor.execute(() -> {
            streamStarted = System.currentTimeMillis();
            try {
                for (StreamEvent event : es.anyEvents()) {
                    if (closed) {
                        break;
                    }
                    if (event instanceof MessageEvent) {
                        handleMessage((MessageEvent) event);
                    } else if (event instanceof FaultEvent) {
                        handleError((FaultEvent) event);
                    }
                    // CommentEvent (SSE comment/heartbeat line) — no action needed
                }
            } catch (Exception e) {
                synchronized (closeLock) {
                    if (closed) {
                        return;
                    }
                }
                LDUtil.logExceptionAtErrorLevel(logger, e, "Stream thread ended with unexpected exception");
                recordStreamInit(true);
                resultQueue.put(FDv2SourceResult.status(
                        FDv2SourceResult.Status.interrupted(
                                new LDFailure("Stream thread ended unexpectedly", e,
                                        LDFailure.FailureType.UNKNOWN_ERROR))));
            } finally {
                es.close();
            }
        });
    }

    private URI getStreamUri() {
        URI uri = HttpHelpers.concatenateUriPath(streamBaseUri, streamRequestPath);
        if (!useReport) {
            uri = HttpHelpers.concatenateUriPath(uri, LDUtil.urlSafeBase64(evaluationContext));
        }
        if (evaluationReasons) {
            uri = HttpHelpers.addQueryParam(uri, "withReasons", "true");
        }
        return uri;
    }

    private void recordStreamInit(boolean failed) {
        long start = streamStarted;
        if (diagnosticStore != null && start != 0) {
            diagnosticStore.recordStreamInit(start, System.currentTimeMillis() - start, failed);
        }
    }

    private static boolean isFdv1Fallback(@Nullable ResponseHeaders headers) {
        if (headers == null) {
            return false;
        }
        String value = headers.value(HeaderConstants.FDV1_FALLBACK_HEADER);
        return value != null && value.equalsIgnoreCase("true");
    }

    @VisibleForTesting
    void handleMessage(MessageEvent event) {
        String eventName = event.getEventName();
        String eventData = event.getData();
        logger.debug("onMessage: {}: {}", eventName, eventData);

        if (PING.equalsIgnoreCase(eventName)) {
            handlePing();
            return;
        }

        boolean fdv1Fallback = isFdv1Fallback(event.getHeaders());

        FDv2Event fdv2Event;
        try {
            fdv2Event = new FDv2Event(eventName,
                    GsonHelpers.gsonInstance().fromJson(eventData, com.google.gson.JsonElement.class));
        } catch (Exception e) {
            LDUtil.logExceptionAtErrorLevel(logger, e, "Failed to parse SSE event '{}'", eventName);
            resultQueue.put(FDv2SourceResult.status(
                    FDv2SourceResult.Status.interrupted(
                            new LDFailure("Failed to parse SSE event", e,
                                    LDFailure.FailureType.INVALID_RESPONSE_BODY)),
                    fdv1Fallback));
            restartStream(true);
            return;
        }

        FDv2ProtocolHandler.IFDv2ProtocolAction action;
        try {
            action = protocolHandler.handleEvent(fdv2Event);
        } catch (Exception e) {
            LDUtil.logExceptionAtErrorLevel(logger, e, "Protocol handler error for event '{}'", eventName);
            resultQueue.put(FDv2SourceResult.status(
                    FDv2SourceResult.Status.interrupted(
                            new LDFailure("Protocol handler error", e,
                                    LDFailure.FailureType.INVALID_RESPONSE_BODY)),
                    fdv1Fallback));
            restartStream(true);
            return;
        }

        switch (action.getAction()) {
            case CHANGESET: {
                FDv2ChangeSet raw = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
                try {
                    ChangeSet<Map<String, Flag>> changeSet =
                            FDv2ChangeSetTranslator.toChangeSet(raw, logger);
                    recordStreamInit(false);
                    streamStarted = 0;
                    resultQueue.put(FDv2SourceResult.changeSet(changeSet, fdv1Fallback));
                } catch (Exception e) {
                    LDUtil.logExceptionAtErrorLevel(logger, e, "Failed to translate changeset");
                    FDv2SourceResult result = FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(
                        new LDFailure("Failed to translate changeset", e,
                                LDFailure.FailureType.INVALID_RESPONSE_BODY)),
                        fdv1Fallback);
                    resultQueue.put(result);
                    restartStream(true);
                }
                break;
            }
            case ERROR: {
                // Per the FDv2 protocol, server-sent error events are non-fatal: the protocol
                // handler discards the payload and we remain connected.
                FDv2ProtocolHandler.FDv2ActionError error =
                        (FDv2ProtocolHandler.FDv2ActionError) action;
                logger.error("Received error from server: {} - {}",
                        error.getId(), error.getReason());
                break;
            }
            case GOODBYE: {
                String reason = ((FDv2ProtocolHandler.FDv2ActionGoodbye) action).getReason();
                logger.info("Stream received GOODBYE with reason: '{}'", reason);
                resultQueue.put(FDv2SourceResult.status(FDv2SourceResult.Status.goodbye(reason), fdv1Fallback));
                restartStream(false);
                break;
            }
            case INTERNAL_ERROR: {
                FDv2ProtocolHandler.FDv2ActionInternalError internalError =
                        (FDv2ProtocolHandler.FDv2ActionInternalError) action;
                logger.error("FDv2 protocol internal error ({}): {}",
                        internalError.getErrorType(), internalError.getMessage());
                resultQueue.put(FDv2SourceResult.status(
                        FDv2SourceResult.Status.interrupted(
                                new LDFailure("FDv2 protocol internal error: " + internalError.getMessage(),
                                        LDFailure.FailureType.INVALID_RESPONSE_BODY)),
                        fdv1Fallback));
                // Only restart for invalid-data errors (bad payload or JSON); for unknown events
                // or protocol sequence violations the stream may still be healthy.
                FDv2ProtocolHandler.FDv2ProtocolErrorType errorType = internalError.getErrorType();
                if (errorType == FDv2ProtocolHandler.FDv2ProtocolErrorType.MISSING_PAYLOAD ||
                        errorType == FDv2ProtocolHandler.FDv2ProtocolErrorType.JSON_ERROR) {
                    restartStream(true);
                }
                break;
            }
            case NONE:
                break;
        }
    }

    /**
     * Handles a {@code ping} SSE event by issuing a poll request synchronously on the calling
     * (streaming) thread. Blocking the streaming thread prevents any subsequent stream events
     * from being processed until the poll response has been enqueued, which ensures ordering
     * and prevents concurrent in-flight requests. If no requestor was supplied at construction,
     * the event is silently ignored.
     */
    private void handlePing() {
        if (requestor == null) {
            logger.debug("Stream received ping but no requestor configured; ignoring");
            return;
        }
        logger.debug("Stream received ping; fetching payload via poll");
        FDv2SourceResult result = FDv2PollingBase.doPoll(requestor, logger, selectorSource.getSelector(), false);
        resultQueue.put(result);
    }

    private void handleError(FaultEvent event) {
        StreamException t = event.getCause();
        if (t instanceof StreamClosedByCallerException) {
            return;
        }

        recordStreamInit(true);
        protocolHandler.reset();

        boolean fdv1Fallback = isFdv1Fallback(event.getHeaders());

        if (t instanceof StreamHttpErrorException) {
            StreamHttpErrorException httpError = (StreamHttpErrorException) t;
            fdv1Fallback = fdv1Fallback || isFdv1Fallback(httpError.getHeaders());
            int code = httpError.getCode();
            boolean recoverable = LDUtil.isHttpErrorRecoverable(code);
            LDFailure failure = new LDInvalidResponseCodeFailure(
                    "Unexpected response code from stream", t, code, recoverable);

            if (!recoverable) {
                logger.error("Encountered non-retriable error: {}. Aborting connection to stream. Verify correct Mobile Key and Stream URI", code);
                shutdownFuture.set(FDv2SourceResult.status(
                        FDv2SourceResult.Status.terminalError(failure), fdv1Fallback));
                EventSource es;
                synchronized (closeLock) {
                    closed = true;
                    es = eventSource;
                    eventSource = null;
                }
                if (es != null) {
                    es.close();
                }
                if (requestor != null) {
                    try {
                        requestor.close();
                    } catch (IOException ignored) {
                    }
                }
            } else {
                logger.warn("Stream received HTTP error {}; will retry", code);
                streamStarted = System.currentTimeMillis();
                resultQueue.put(FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), fdv1Fallback));
            }
        } else {
            LDUtil.logExceptionAtWarnLevel(logger, t, "Stream network error");
            streamStarted = System.currentTimeMillis();
            resultQueue.put(FDv2SourceResult.status(
                    FDv2SourceResult.Status.interrupted(
                            new LDFailure("Stream network error", t,
                                    LDFailure.FailureType.NETWORK_FAILURE)),
                    fdv1Fallback));
        }
    }

    /**
     * Interrupts the current connection so the EventSource reconnects immediately on the
     * streaming thread, and resets the diagnostic timer for the new connection attempt.
     * {@link EventSource#interrupt()} is safe to call from the streaming thread itself.
     *
     * @param failed true if the restart is due to an error (for diagnostic recording)
     */
    private void restartStream(boolean failed) {
        recordStreamInit(failed);
        streamStarted = System.currentTimeMillis();

        EventSource es;
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            es = eventSource;
        }

        if (es != null) {
            es.interrupt();
        }
        protocolHandler.reset();
    }
}
