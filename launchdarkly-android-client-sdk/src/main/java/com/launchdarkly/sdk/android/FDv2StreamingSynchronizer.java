package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;
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
import com.launchdarkly.sdk.json.SerializationException;

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
 * Maintains a long-lived SSE connection using the Android EventSource library (callback-based
 * API). Incoming SSE events are processed through {@link FDv2ProtocolHandler}; CHANGESET
 * actions are translated via {@link FDv2ChangeSetTranslator} and delivered as
 * {@link FDv2SourceResult} values through {@link #next()}.
 * <p>
 * If an optional {@link FDv2Requestor} is supplied, {@code ping} SSE events are handled by
 * issuing a poll request. If no requestor is supplied, {@code ping} events are ignored.
 * <p>
 * On GOODBYE the stream is restarted: the EventSource is closed and a new one is created on
 * the next {@link #next()} call, so the server can send a fresh payload. Non-recoverable HTTP
 * errors (e.g. 401) deliver a TERMINAL_ERROR that completes the shutdown future, preventing
 * further reconnects and signalling the orchestrator to move on.
 * <p>
 * Thread safety: EventSource callbacks run on the EventSource's internal thread. {@link #next()}
 * and {@link #close()} may be called from the FDv2DataSource orchestrator thread. Shared mutable
 * state is guarded either by {@link #closeLock} (for start/close/restart lifecycle) or by
 * being volatile ({@code eventSource}, {@code streamStarted}).
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
    // started: true once startStream() has been called; reset to false on restart so the
    // next next() call creates a new EventSource.
    private final AtomicBoolean started = new AtomicBoolean(false);

    // closeLock guards: closed, eventSource, and started.set(false) in restartStream.
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
     * @param executor                     executor used for closing the EventSource when restarting the
     *                                     stream; closing must not run on the EventSource callback thread
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
        // Check outside of closeLock to avoid holding it during startStream().
        // startStream() itself holds closeLock only while assigning eventSource, so the
        // window is short. See restartStream() for why started may be reset to false.
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
        FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

        EventHandler eventHandler = new EventHandler() {
            @Override
            public void onOpen() {
                logger.info("Connected to FDv2 stream");
                long start = streamStarted;
                if (diagnosticStore != null && start != 0) {
                    diagnosticStore.recordStreamInit(start,
                            (int) (System.currentTimeMillis() - start), false);
                    streamStarted = 0;
                }
            }

            @Override
            public void onClosed() {
                logger.debug("FDv2 stream closed");
            }

            @Override
            public void onMessage(String name, MessageEvent event) {
                logger.debug("FDv2 stream event: {}", name);
                handleSseMessage(name, event.getData(), handler);
            }

            @Override
            public void onComment(String comment) {
                // heartbeat — no action needed
            }

            @Override
            public void onError(Throwable t) {
                handleSseError(t);
            }
        };

        EventSource.Builder builder = new EventSource.Builder(eventHandler, getStreamUri());
        builder.reconnectTime(initialReconnectDelayMillis, TimeUnit.MILLISECONDS);
        builder.maxReconnectTime(MAX_RECONNECT_TIME_MS, TimeUnit.MILLISECONDS);

        builder.clientBuilderActions(clientBuilder -> {
            httpProperties.applyToHttpClientBuilder(clientBuilder);
            clientBuilder.readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        });

        // Inject headers and selector (basis) query param on each (re)connection.
        builder.requestTransformer(request -> {
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
            builder.method(METHOD_REPORT);
            builder.body(RequestBody.create(JsonSerialization.serialize(evaluationContext), JSON));
        }

        EventSource es = builder.build();

        synchronized (closeLock) {
            if (closed) {
                return;
            }
            eventSource = es;
        }

        streamStarted = System.currentTimeMillis();
        es.start();
    }

    private URI getStreamUri() {
        URI uri = HttpHelpers.concatenateUriPath(streamBaseUri, streamRequestPath);
        if (!useReport) {
            uri = HttpHelpers.concatenateUriPath(uri, LDUtil.urlSafeBase64(evaluationContext));
        }
        if (evaluationReasons) {
            uri = URI.create(uri.toString() + "?withReasons=true");
        }
        return uri;
    }

    @VisibleForTesting
    void handleSseMessage(String eventName, String eventData, FDv2ProtocolHandler handler) {
        if (PING.equalsIgnoreCase(eventName)) {
            handlePing();
            return;
        }

        FDv2Event fdv2Event;
        try {
            fdv2Event = new FDv2Event(eventName,
                    GsonHelpers.gsonInstance().fromJson(eventData, com.google.gson.JsonElement.class));
        } catch (Exception e) {
            logger.error("Failed to parse FDv2 SSE event '{}': {}", eventName, e.toString());
            resultQueue.put(FDv2SourceResult.status(
                    FDv2SourceResult.Status.interrupted(
                            new LDFailure("Failed to parse FDv2 SSE event", e,
                                    LDFailure.FailureType.INVALID_RESPONSE_BODY))));
            restartStream(true);
            return;
        }

        FDv2ProtocolHandler.IFDv2ProtocolAction action;
        try {
            action = handler.handleEvent(fdv2Event);
        } catch (Exception e) {
            logger.error("FDv2 protocol handler error for event '{}': {}", eventName, e.toString());
            resultQueue.put(FDv2SourceResult.status(
                    FDv2SourceResult.Status.interrupted(
                            new LDFailure("FDv2 protocol handler error", e,
                                    LDFailure.FailureType.INVALID_RESPONSE_BODY))));
            restartStream(true);
            return;
        }

        switch (action.getAction()) {
            case CHANGESET: {
                FDv2ChangeSet raw = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
                try {
                    ChangeSet<Map<String, Flag>> changeSet =
                            FDv2ChangeSetTranslator.toChangeSet(raw, logger);
                    long start = streamStarted;
                    if (diagnosticStore != null && start != 0) {
                        diagnosticStore.recordStreamInit(start,
                                (int) (System.currentTimeMillis() - start), false);
                        streamStarted = 0;
                    }
                    resultQueue.put(FDv2SourceResult.changeSet(changeSet));
                } catch (SerializationException e) {
                    logger.error("Failed to translate FDv2 changeset: {}", e.toString());
                    resultQueue.put(FDv2SourceResult.status(
                            FDv2SourceResult.Status.interrupted(
                                    new LDFailure("Failed to translate FDv2 changeset", e,
                                            LDFailure.FailureType.INVALID_RESPONSE_BODY))));
                    restartStream(true);
                }
                break;
            }
            case ERROR: {
                // Server-side error event: log and remain connected; no result queued.
                FDv2ProtocolHandler.FDv2ActionError error =
                        (FDv2ProtocolHandler.FDv2ActionError) action;
                logger.error("Received FDv2 error from server: {} - {}",
                        error.getId(), error.getReason());
                break;
            }
            case GOODBYE: {
                String reason = ((FDv2ProtocolHandler.FDv2ActionGoodbye) action).getReason();
                logger.info("FDv2 stream received GOODBYE with reason: '{}'", reason);
                resultQueue.put(FDv2SourceResult.status(FDv2SourceResult.Status.goodbye(reason)));
                // Restart so the next next() call establishes a new connection with the
                // current selector, potentially receiving an incremental changeset.
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
                                        LDFailure.FailureType.INVALID_RESPONSE_BODY))));
                restartStream(true);
                break;
            }
            case NONE:
                // heartbeat / server-intent with no immediate action
                break;
        }
    }

    /**
     * Handles a {@code ping} SSE event by issuing a poll request synchronously on the calling
     * (EventSource delivery) thread. Blocking the delivery thread prevents any subsequent stream
     * events from being processed until the poll response has been enqueued, which ensures
     * ordering and prevents concurrent in-flight requests. Response processing is delegated to
     * {@link FDv2PollingBase#doPoll(FDv2Requestor, LDLogger, Selector, boolean)} with
     * {@code oneShot=false} so errors map to INTERRUPTED rather than TERMINAL_ERROR.
     * If no requestor was supplied at construction, the event is silently ignored.
     */
    private void handlePing() {
        if (requestor == null) {
            logger.debug("FDv2 stream received ping but no requestor configured; ignoring");
            return;
        }
        logger.debug("FDv2 stream received ping; fetching payload via poll");
        FDv2SourceResult result = FDv2PollingBase.doPoll(requestor, logger, selectorSource.getSelector(), false);
        resultQueue.put(result);
    }

    private void handleSseError(Throwable t) {
        if (t instanceof UnsuccessfulResponseException) {
            int code = ((UnsuccessfulResponseException) t).getCode();
            boolean recoverable = LDUtil.isHttpErrorRecoverable(code);
            LDFailure failure = new LDInvalidResponseCodeFailure(
                    "Unexpected response code from FDv2 stream", t, code, recoverable);

            if (diagnosticStore != null) {
                long start = streamStarted;
                if (start != 0) {
                    diagnosticStore.recordStreamInit(start,
                            (int) (System.currentTimeMillis() - start), true);
                }
            }

            if (!recoverable) {
                logger.error("FDv2 stream received non-recoverable HTTP error: {}", code);
                // TERMINAL_ERROR on shutdownFuture ensures all current and future next() calls
                // return immediately without waiting for the queue.
                // The orchestrator (FDv2DataSource) is responsible for calling shutDown() on the
                // sink if the terminal error is due to an auth failure.
                shutdownFuture.set(FDv2SourceResult.status(
                        FDv2SourceResult.Status.terminalError(failure)));
            } else {
                logger.warn("FDv2 stream received HTTP error {}; will retry", code);
                streamStarted = System.currentTimeMillis();
                resultQueue.put(FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure)));
            }
        } else {
            LDUtil.logExceptionAtWarnLevel(logger, t, "FDv2 stream network error");
            streamStarted = System.currentTimeMillis();
            resultQueue.put(FDv2SourceResult.status(
                    FDv2SourceResult.Status.interrupted(
                            new LDFailure("FDv2 stream network error", t,
                                    LDFailure.FailureType.NETWORK_FAILURE))));
        }
    }

    /**
     * Closes the current EventSource and resets state so the next {@link #next()} call starts
     * a fresh connection. The protocol handler is reset to the INACTIVE state so the new
     * connection can receive a new server-intent sequence.
     * <p>
     * Called from within the EventSource callback thread; the actual close happens in a
     * background thread to avoid blocking the callback.
     *
     * @param failed true if the restart is due to an error (for diagnostic recording)
     */
    private void restartStream(boolean failed) {
        long start = streamStarted;
        if (diagnosticStore != null && start != 0) {
            diagnosticStore.recordStreamInit(start,
                    (int) (System.currentTimeMillis() - start), failed);
        }
        streamStarted = System.currentTimeMillis();

        EventSource esToClose;
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            esToClose = eventSource;
            eventSource = null;
            // Reset started so the next next() call creates a new EventSource.
            started.set(false);
        }

        if (esToClose != null) {
            // Close via executor to avoid blocking or deadlocking the EventSource callback
            // thread (restartStream is invoked from handleSseMessage on that thread).
            EventSource toClose = esToClose;
            executor.execute(toClose::close);
        }
    }
}
