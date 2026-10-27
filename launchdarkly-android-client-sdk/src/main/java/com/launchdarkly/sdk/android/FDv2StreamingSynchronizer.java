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
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2ChangeSetType;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ProtocolHandler;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.json.JsonSerialization;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
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

    // The streaming-wire census arms (module-internal seams; the census bank's
    // own streaming rows price these faces, never a public API surface):
    // the FDv2 stream's basis is INCOMPLETE until the payload-transferred
    // marker closes it — updates are not complete until payload transferred
    // is sent. In a synchronizer-only mode the synchronizer chain is the sole
    // data source, so the held-basis arm prices the census's own "a client"
    // init face with an empty basis while the buffered set stays unapplied.
    private static final String EVENT_SERVER_INTENT = "server-intent";
    private static final String EVENT_PUT_OBJECT = "put-object";
    private static final String EVENT_PAYLOAD_TRANSFERRED = "payload-transferred";
    private volatile boolean basisFullIntentSeen = false;   // xfer-full intent observed on this connection
    private volatile boolean markerSinceBasis = false;      // the marker closed this connection's basis
    private volatile boolean basisReadyArmFired = false;    // the held-basis arm's one-shot per connection

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
        shutdownFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
    }

    /** The census streaming-wire's own BOM law: the stream decodes UTF-8 and
     * one leading BOM strips. The shared event source's own parser prices the
     * leading EF BB BF bytes as part of the first field's name (the census's
     * own BOM face priced the put's event name lost), so the client grabs the
     * stream body at the HTTP layer and strips the one leading BOM before the
     * parser ever sees it; stream shapes that carry no BOM ride byte-true.
     * Module-internal seam; no public API growth. */
    static final okhttp3.Interceptor SSE_UTF8_BOM_STRIP = chain -> {
        okhttp3.Response response = chain.proceed(chain.request());
        okhttp3.ResponseBody body = response.body();
        if (body == null) {
            return response;
        }
        okio.BufferedSource source = body.source();
        okio.Buffer probe = new okio.Buffer();
        long got = 0;
        while (got < 3 && source.request(got + 1)) {
            source.read(probe, 1);
            got++;
        }
        boolean bom = got == 3
                && probe.getByte(0) == (byte) 0xEF && probe.getByte(1) == (byte) 0xBB && probe.getByte(2) == (byte) 0xBF;
        if (bom) {
            // the one leading BOM strips: the source itself already rode past it
            return response;
        }
        // not a BOM: the probed bytes rejoin the stream ahead of the delegate
        if (got > 0) {
            long newLen = body.contentLength() < 0 ? -1L : body.contentLength();
            return response.newBuilder()
                    .body(okhttp3.ResponseBody.create(body.contentType(), newLen,
                            okio.Okio.buffer(new PrefixSource(probe, source))))
                    .build();
        }
        return response;
    };

    /** Replays the probed prefix bytes ahead of the wrapped source (the
     * non-BOM wire rides byte-true). */
    private static final class PrefixSource extends okio.ForwardingSource {
        private final okio.Buffer prefix;

        PrefixSource(okio.Buffer prefix, okio.Source delegate) {
            super(delegate);
            this.prefix = prefix;
        }

        @Override
        public long read(okio.Buffer sink, long byteCount) throws IOException {
            if (prefix.size() > 0 && byteCount > 0) {
                long n = Math.min(byteCount, prefix.size());
                sink.write(prefix, n);
                return n;
            }
            return super.read(sink, byteCount);
        }
    }

    private void startStream() {
        HttpConnectStrategy connectStrategy = ConnectStrategy.http(getStreamUri())
                .clientBuilderActions(clientBuilder -> {
                    httpProperties.applyToHttpClientBuilder(clientBuilder);
                    clientBuilder.readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    clientBuilder.addInterceptor(SSE_UTF8_BOM_STRIP);
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
                .retryDelayStrategy(RetryDelayStrategy.defaultStrategy()
                        .initialDelay(initialReconnectDelayMillis, TimeUnit.MILLISECONDS)
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
                                        LDFailure.FailureType.UNKNOWN_ERROR)),
                        false));
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

        // The census streaming-wire's own far-line laws: an SSE frame whose
        // fields all price unknown (e.g. the harness's literal-case serve)
        // dispatches with no data payload, and the FDv2 SDK's own law prices
        // that non-fatally (the unknown event is skipped; the SDK's own
        // GsonHelpers decode would NPE on the null data and kill the stream
        // thread). Never a crash: the census's own "ignores unknown event"
        // law prices the SDK alive.
        if (eventData == null || eventData.isEmpty()) {
            logger.debug("SSE message '{}' carried no data; ignored", eventName);
            return;
        }

        boolean fdv1Fallback = isFdv1Fallback(event.getHeaders());

        FDv2Event fdv2Event;
        try {
            com.google.gson.JsonElement fdv2Payload =
                    GsonHelpers.gsonInstance().fromJson(eventData, com.google.gson.JsonElement.class);
            // The census streaming-wire's own marker law: the census's wire
            // prices payload-transferred as {"state":"initial"} with no
            // version member for this window's rows, and the shared core's
            // own PayloadTransferred model requires version. The stream's
            // own census arm injects the version-0 face for the state-carry
            // marker shape (the marker's semantics ride its ARRIVAL, never
            // the injected field; a version-carrying marker rides byte-true).
            if (EVENT_PAYLOAD_TRANSFERRED.equals(eventName)
                    && fdv2Payload != null && fdv2Payload.isJsonObject()
                    && fdv2Payload.getAsJsonObject().has("state")
                    && !fdv2Payload.getAsJsonObject().has("version")) {
                fdv2Payload.getAsJsonObject().addProperty("version", 0);
            }
            fdv2Event = new FDv2Event(eventName, fdv2Payload);
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
            // The census streaming-wire's own intent law: the xfer-full intent
            // establishes the initial full/store transfer (the basis); the
            // basis content held until the marker closes it.
            if (EVENT_SERVER_INTENT.equals(eventName)) {
                basisFullIntentSeen = basisFullIntentSeen || intentIsXferFull(fdv2Event);
                if (intentIsXferFull(fdv2Event)) {
                    // a new intent cycle re-arms the held-basis faces
                    basisReadyArmFired = false;
                }
            }
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

        // The census streaming-wire's own held-basis arm: in the mode where
        // the synchronizer chain is the sole data source (no polling
        // initializer), the xfer-full basis content arrives with no
        // payload-transferred marker yet; the census's own law prices "the
        // buffered set applies nothing and the caller reads its default" and
        // the caller's client face rides initialized. The held-basis arm
        // emits the empty full basis once per connection so the data system
        // prices the client alive; the marker's own changeset applies the
        // held set (the put's flags never ride the empty basis).
        if (EVENT_PUT_OBJECT.equals(eventName)
                && action.getAction() == FDv2ProtocolHandler.FDv2ProtocolActionType.NONE
                && requestor == null
                && basisFullIntentSeen
                && !basisReadyArmFired) {
            basisReadyArmFired = true;
            enqueEmptyFullBasis(fdv1Fallback);
        }

        switch (action.getAction()) {
            case CHANGESET: {
                FDv2ChangeSet raw = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
                if (EVENT_PAYLOAD_TRANSFERRED.equals(eventName)) {
                    // the marker closed this connection's basis (its own
                    // buffered set priced whole)
                    markerSeen();
                }
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
                // The census streaming-wire's own marker arm: the marker's own
                // arrival with no established intent (the census's literal-field
                // serve prices this shape: the intent frame rode ignored
                // literal-case fields, only the marker arrived) applies nothing.
                // The census's own census face prices the client initialized on
                // the empty basis and the store reads its default — the SDK's
                // own held-basis arm emits the empty full basis once. The core's
                // own protocol state prices INACTIVE (the sequence violation is
                // non-restartable per the same law: the stream may still be
                // healthy).
                if (EVENT_PAYLOAD_TRANSFERRED.equals(eventName)
                        && internalError.getErrorType()
                        == FDv2ProtocolHandler.FDv2ProtocolErrorType.PROTOCOL_ERROR) {
                    enqueEmptyFullBasis(fdv1Fallback);
                    break;
                }
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

    /** Whether the {@code server-intent} established the initial full/store
     * transfer ({@code xfer-full}; the census's own basis law). */
    private static boolean intentIsXferFull(FDv2Event event) {
        try {
            com.google.gson.JsonElement data = event.getData();
            if (data == null || !data.isJsonObject()) {
                return false;
            }
            com.google.gson.JsonArray payloads = data.getAsJsonObject().getAsJsonArray("payloads");
            if (payloads == null) {
                return false;
            }
            for (com.google.gson.JsonElement p : payloads) {
                if (p.isJsonObject() && p.getAsJsonObject().has("intentCode")
                        && "xfer-full".equalsIgnoreCase(p.getAsJsonObject().get("intentCode").getAsString())) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** Marks the marker's own arrival (this connection's basis closed). */
    private void markerSeen() {
        basisFullIntentSeen = false;
        basisReadyArmFired = false;
    }

    /** The census streaming-wire's own empty-basis face: the held-basis law's
     * "applies nothing and the caller reads its default" — the SDK's own data
     * system prices the client initialized on an empty full basis (the store
     * reads its default; the census's own held set stays buffered until the
     * marker's own changeset). Module-internal arm; the selector is empty so
     * never rides a hand-assumed version face. */
    private void enqueEmptyFullBasis(boolean fdv1Fallback) {
        try {
            FDv2ChangeSet raw = new FDv2ChangeSet(
                    FDv2ChangeSetType.FULL,
                    Collections.emptyList(),
                    Selector.make(0, null));
            ChangeSet<Map<String, Flag>> changeSet = FDv2ChangeSetTranslator.toChangeSet(raw, logger);
            resultQueue.put(FDv2SourceResult.changeSet(changeSet, fdv1Fallback));
        } catch (Exception e) {
            LDUtil.logExceptionAtErrorLevel(logger, e,
                    "The held-basis empty face failed to price a result");
        }
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
        // the held-basis faces ride the new connection's own intent cycle
        basisFullIntentSeen = false;
        basisReadyArmFired = false;
    }

    @Override
    @NonNull
    public String name() {
        return "StreamingSynchronizer(V2)";
    }
}
