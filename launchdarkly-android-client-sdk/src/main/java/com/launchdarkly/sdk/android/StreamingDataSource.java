package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.launchdarkly.eventsource.ConnectStrategy;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.HttpConnectStrategy;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.StreamException;
import com.launchdarkly.eventsource.StreamHttpErrorException;
import com.launchdarkly.eventsource.background.BackgroundEventHandler;
import com.launchdarkly.eventsource.background.BackgroundEventSource;
import com.launchdarkly.eventsource.background.ConnectionErrorHandler;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.json.JsonSerialization;
import com.launchdarkly.sdk.json.SerializationException;

import java.net.URI;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.RequestBody;

import static com.launchdarkly.sdk.android.LDConfig.JSON;
import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

/**
 * Data source implementation for streaming mode.
 * <p>
 * The SDK uses this implementation if streaming is enabled (as it is by default) and the
 * application is the foreground. The logic for this is in ComponentsImpl.StreamingDataSourceBuilderImpl.
 * <p>
 * Reconnection is managed by this class rather than by the EventSource library, so that the
 * backoff is controlled here: every stream failure, including HTTP statuses such as
 * 401 and 403 that used to stop the stream permanently, is retried. Failures that are unlikely to
 * resolve on their own are retried with a much longer backoff (see {@link RetryState}).
 */
final class StreamingDataSource implements DataSource {
    private static final String METHOD_REPORT = "REPORT";

    private static final String PING = "ping";
    private static final String PUT = "put";
    private static final String PATCH = "patch";
    private static final String DELETE = "delete";

    private static final long READ_TIMEOUT_MS = 300_000;
    // 5 minutes is the standard read timeout used for all LaunchDarkly stream connections, based on
    // an expectation that the server will send heartbeats at a shorter interval than that

    private final LDContext context;
    private final HttpProperties httpProperties;
    private final boolean evaluationReasons;
    final int initialReconnectDelayMillis; // visible for testing
    private final boolean useReport;
    private final URI streamUri;
    private final DataSourceUpdateSink dataSourceUpdateSink;
    private final FeatureFetcher fetcher;
    private final boolean streamEvenInBackground;
    private final DiagnosticStore diagnosticStore;
    private final TaskExecutor taskExecutor;
    private final LDLogger logger;

    // The following fields are guarded by the lock on this instance. retryState is only ever
    // touched while holding that lock.
    private final RetryState retryState;
    private BackgroundEventSource es;
    private BackgroundEventHandler handler;
    private ScheduledFuture<?> pendingReconnect;
    private boolean running = false;

    // Written when a connection attempt begins, read by the EventSource callbacks for diagnostics.
    private volatile long eventSourceStarted;

    StreamingDataSource(
            @NonNull ClientContext clientContext,
            @NonNull LDContext context,
            @NonNull DataSourceUpdateSink dataSourceUpdateSink,
            @NonNull FeatureFetcher fetcher,
            int initialReconnectDelayMillis,
            boolean streamEvenInBackground
    ) {
        this(clientContext, context, dataSourceUpdateSink, fetcher, initialReconnectDelayMillis,
                streamEvenInBackground, RetryState.forStreaming(initialReconnectDelayMillis));
    }

    /**
     * This constructor allows tests to supply a {@link RetryState} with short delays.
     */
    StreamingDataSource(
            @NonNull ClientContext clientContext,
            @NonNull LDContext context,
            @NonNull DataSourceUpdateSink dataSourceUpdateSink,
            @NonNull FeatureFetcher fetcher,
            int initialReconnectDelayMillis,
            boolean streamEvenInBackground,
            @NonNull RetryState retryState
    ) {
        this.context = context;
        this.dataSourceUpdateSink = dataSourceUpdateSink;
        this.fetcher = fetcher;
        this.streamUri = clientContext.getServiceEndpoints().getStreamingBaseUri();
        this.httpProperties = LDUtil.makeHttpProperties(clientContext);
        this.evaluationReasons = clientContext.isEvaluationReasons();
        this.useReport = clientContext.getHttp().isUseReport();
        this.initialReconnectDelayMillis = initialReconnectDelayMillis;
        this.streamEvenInBackground = streamEvenInBackground;
        this.diagnosticStore = ClientContextImpl.get(clientContext).getDiagnosticStore();
        this.taskExecutor = ClientContextImpl.get(clientContext).getTaskExecutor();
        this.retryState = retryState;
        this.logger = clientContext.getBaseLogger();
    }

    public void start(@NonNull Callback<Boolean> resultCallback) {
        synchronized (this) {
            if (running) {
                return;
            }
            running = true;
            handler = makeHandler(resultCallback);
        }
        logger.debug("Starting.");
        connect();
    }

    private BackgroundEventHandler makeHandler(@NonNull Callback<Boolean> resultCallback) {
        return new BackgroundEventHandler() {
            @Override
            public void onOpen() {
                logger.info("Started LaunchDarkly EventStream");
                if (diagnosticStore != null) {
                    diagnosticStore.recordStreamInit(eventSourceStarted, (int) (System.currentTimeMillis() - eventSourceStarted), false);
                }
            }

            @Override
            public void onClosed() {
                logger.info("Closed LaunchDarkly EventStream");
            }

            @Override
            public void onMessage(final String name, MessageEvent event) {
                final String eventData = event.getData();
                logger.debug("onMessage: {}: {}", name, eventData);
                // A payload on the stream is healthy operation; enough of it in a row resets
                // the backoff.
                synchronized (StreamingDataSource.this) {
                    retryState.recordSuccess(System.currentTimeMillis());
                }
                handle(name, eventData, resultCallback);
            }

            @Override
            public void onComment(String comment) {
                // intentionally empty
            }

            @Override
            public void onError(Throwable t) {
                LDUtil.logExceptionAtErrorLevel(logger, t,
                        "Encountered EventStream error connecting to URI: {}",
                        getUri(context));

                LDFailure failure;
                boolean unexpected = false;
                int code = 0;
                if (t instanceof StreamHttpErrorException) {
                    if (diagnosticStore != null) {
                        diagnosticStore.recordStreamInit(eventSourceStarted, (int) (System.currentTimeMillis() - eventSourceStarted), true);
                    }
                    code = ((StreamHttpErrorException) t).getCode();
                    boolean recoverable = LDUtil.isHttpErrorRecoverable(code);
                    unexpected = !recoverable;
                    failure = new LDInvalidResponseCodeFailure("Unexpected Response Code From Stream Connection", t, code, recoverable);
                } else {
                    failure = new LDFailure("Network error in stream connection", t, LDFailure.FailureType.NETWORK_FAILURE);
                }

                // A StreamException means the connection has ended; every such transport failure
                // is a normal failure. Anything else was thrown by this handler while
                // processing an event, and the stream is still open, so there is nothing to retry.
                if (t instanceof StreamException) {
                    long delay = scheduleReconnectAfterFailure(unexpected);
                    if (delay >= 0) {
                        if (unexpected) {
                            logger.error("Encountered HTTP error {} from stream. This is not expected to resolve on its own; verify correct Mobile Key and Stream URI. Will retry in {} ms.", code, delay);
                        } else {
                            logger.warn("Will retry stream connection in {} ms.", delay);
                        }
                    }
                }
                resultCallback.onError(failure);
            }
        };
    }

    /**
     * Opens a new stream connection if this data source is still running. Called from
     * {@link #start} and from the reconnect task scheduled by {@link #scheduleReconnectAfterFailure}.
     */
    private synchronized void connect() {
        if (!running) {
            return;
        }
        pendingReconnect = null;

        HttpConnectStrategy connectStrategy = ConnectStrategy.http(getUri(context))
                .clientBuilderActions(clientBuilder -> {
                    httpProperties.applyToHttpClientBuilder(clientBuilder);
                    clientBuilder.readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                })
                .requestTransformer(input ->
                        input.newBuilder()
                                .headers(
                                        input.headers().newBuilder().addAll(httpProperties.toHeadersBuilder().build()).build()
                                ).build());

        if (useReport) {
            connectStrategy = connectStrategy.methodAndBody(METHOD_REPORT, getRequestBody(context));
        }

        EventSource.Builder esBuilder = new EventSource.Builder(connectStrategy);

        eventSourceStarted = System.currentTimeMillis();
        // The previous BackgroundEventSource, if any, has already shut itself down: the connection
        // error handler below ends the stream after every failure, and BackgroundEventSource
        // closes itself when that happens.
        es = new BackgroundEventSource.Builder(handler, esBuilder)
                // The stream thread asks this handler, before it would reconnect, whether an
                // error ends the stream. It always does: this class schedules its own reconnect
                // from onError with a delay from RetryState. Deciding here, on the stream
                // thread, means the library can never race ahead with a reconnect of its own.
                .connectionErrorHandler(t -> ConnectionErrorHandler.Action.SHUTDOWN)
                .build();
        es.start();
    }

    /**
     * Records a stream failure and schedules the next connection attempt.
     *
     * @param unexpected whether the failure is classified as {@code unexpected}
     * @return the delay before the next attempt in milliseconds, or -1 if the data source has been
     * stopped and no reconnect was scheduled
     */
    private synchronized long scheduleReconnectAfterFailure(boolean unexpected) {
        if (!running) {
            return -1;
        }
        retryState.recordFailure(unexpected, System.currentTimeMillis());
        long delay = retryState.nextDelayMillis();
        if (pendingReconnect != null) {
            pendingReconnect.cancel(false);
        }
        pendingReconnect = taskExecutor.scheduleTask(this::connect, delay);
        return delay;
    }

    @NonNull
    private RequestBody getRequestBody(@Nullable LDContext context) {
        logger.debug("Attempting to report user in stream");
        return RequestBody.create(JsonSerialization.serialize(context), JSON);
    }

    private URI getUri(@Nullable LDContext context) {
        // Here we're using java.net.URI and our own URI-building helpers, rather than android.net.Uri
        // and methods like Uri.withAppendedPath, simply to minimize the amount of code that relies on
        // Android-specific APIs so our components are more easily unit-testable.
        URI uri = HttpHelpers.concatenateUriPath(streamUri,
                StandardEndpoints.STREAMING_REQUEST_BASE_PATH);

        if (!useReport && context != null) {
            uri = HttpHelpers.concatenateUriPath(uri, LDUtil.urlSafeBase64(context));
        }

        if (evaluationReasons) {
            uri = URI.create(uri.toString() + "?withReasons=true");
        }

        return uri;
    }

    @VisibleForTesting
    void handle(final String name, final String eventData,
                        @NonNull final Callback<Boolean> resultCallback) {
        switch (name.toLowerCase()) {
            case PUT:
                EnvironmentData data;
                try {
                    data = EnvironmentData.fromJson(eventData);
                } catch (Exception e) {
                    logger.debug("Received invalid JSON flag data: {}", eventData);
                    resultCallback.onError(new LDFailure("Invalid JSON received from flags endpoint",
                            e, LDFailure.FailureType.INVALID_RESPONSE_BODY));
                    return;
                }
                dataSourceUpdateSink.init(context, data.getAll());
                resultCallback.onSuccess(true);
                break;
            case PATCH:
                applyPatch(eventData, resultCallback);
                break;
            case DELETE:
                applyDelete(eventData, resultCallback);
                break;
            case PING:
                ConnectivityManager.fetchAndSetData(fetcher, context, dataSourceUpdateSink,
                        resultCallback, logger);
                break;
            default:
                logger.debug("Found an unknown stream protocol: {}", name);
                resultCallback.onError(new LDFailure("Unknown Stream Element Type", null, LDFailure.FailureType.UNEXPECTED_STREAM_ELEMENT_TYPE));
        }
    }

    @Override
    public void stop(final @NonNull Callback<Void> onCompleteListener) {
        logger.debug("Stopping.");
        // We do this in a separate thread because closing the stream involves a network
        // operation and we don't want to do a network operation on the main thread.
        // This code originally created a one-shot thread for shutting down the event source, but at some point
        // an Executor was introduced.  A thread leak bug was introduced with that Executor because the Executor
        // was not cleaned up.  The thread leak bug was brought to our attention in
        // https://github.com/launchdarkly/android-client-sdk/issues/234 .  Over time, the code evolved to no longer
        // need the Executor to be long lived.  Reverting to the one-shot thread approach is sufficient to address
        // the bug.  A more appropriate fix would be to refactor/unify the various task executors in the code base
        // and pass one of those executors in to be used for this purpose.  That refactoring is not without risk and
        // will be reserved for a future major version.
        new Thread(() -> {
            // Moves the current Thread into the background.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            stopSync();
            if (onCompleteListener != null) {
                onCompleteListener.onSuccess(null);
            }
        }).start();
    }

    @Override
    public boolean needsRefresh(boolean newInBackground, LDContext newEvaluationContext) {
        return !newEvaluationContext.equals(context) ||
                (newInBackground && !streamEvenInBackground);
    }

    private void stopSync() {
        BackgroundEventSource esToClose;
        synchronized (this) {
            running = false;
            // A pending backoff wait is interrupted immediately by shutdown.
            if (pendingReconnect != null) {
                pendingReconnect.cancel(false);
                pendingReconnect = null;
            }
            esToClose = es;
            es = null;
        }
        // Closing waits for the EventSource's threads to finish, so it is done outside the lock
        // in case one of those threads is in a callback that needs the lock.
        if (esToClose != null) {
            esToClose.close();
        }
        logger.debug("Stopped.");
    }

    private void applyPatch(String json, @NonNull final Callback<Boolean> onCompleteListener) {
        Flag flag;
        try {
            flag = Flag.fromJson(json);
        } catch (SerializationException e) {
            logger.debug("Invalid PATCH payload: {}", json);
            onCompleteListener.onError(new LDFailure("Invalid PATCH payload",
                    LDFailure.FailureType.INVALID_RESPONSE_BODY));
            return;
        }
        if (flag == null) {
            return;
        }
        dataSourceUpdateSink.upsert(context, flag);
        onCompleteListener.onSuccess(null);
    }

    private void applyDelete(String json, @NonNull final Callback<Boolean> onCompleteListener) {
        DeleteMessage deleteMessage;
        try {
            deleteMessage = gsonInstance().fromJson(json, DeleteMessage.class);
        } catch (Exception e) {
            logger.debug("Invalid DELETE payload: {}", json);
            onCompleteListener.onError(new LDFailure("Invalid DELETE payload",
                    LDFailure.FailureType.INVALID_RESPONSE_BODY));
            return;
        }
        if (deleteMessage == null) {
            return;
        }
        dataSourceUpdateSink.upsert(context, Flag.deletedItemPlaceholder(deleteMessage.key, deleteMessage.version));
        onCompleteListener.onSuccess(null);
    }

    private static final class DeleteMessage {
        private final String key;
        private final int version;

        DeleteMessage(String key, int version) {
            this.key = key;
            this.version = version;
        }
    }
}
