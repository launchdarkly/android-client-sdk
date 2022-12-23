package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

import static com.launchdarkly.sdk.android.LDConfig.JSON;
import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

/**
 * Data source implementation for streaming mode.
 * <p>
 * The SDK uses this implementation if streaming is enabled (as it is by default) and the
 * application is the foreground. The logic for this is in ComponentsImpl.StreamingDataSourceBuilderImpl.
 */
final class StreamingDataSource implements DataSource {
    private static final String METHOD_REPORT = "REPORT";

    private static final String PING = "ping";
    private static final String PUT = "put";
    private static final String PATCH = "patch";
    private static final String DELETE = "delete";

    private static final long MAX_RECONNECT_TIME_MS = 3_600_000; // 1 hour

    private static final long READ_TIMEOUT_MS = 300_000;
    // 5 minutes is the standard read timeout used for all LaunchDarkly stream connections, based on
    // an expectation that the server will send heartbeats at a shorter interval than that

    private EventSource es;
    private final LDContext currentContext;
    private final HttpProperties httpProperties;
    private final boolean evaluationReasons;
    final int initialReconnectDelayMillis; // visible for testing
    private final boolean useReport;
    private final URI streamUri;
    private final DataSourceUpdateSink dataSourceUpdateSink;
    private final FeatureFetcher fetcher;
    private final boolean streamEvenInBackground;
    private volatile boolean running = false;
    private boolean connection401Error = false;
    private final ExecutorService executor;
    private final DiagnosticStore diagnosticStore;
    private long eventSourceStarted;
    private final LDLogger logger;

    StreamingDataSource(
            @NonNull ClientContext clientContext,
            @NonNull LDContext currentContext,
            @NonNull DataSourceUpdateSink dataSourceUpdateSink,
            @NonNull FeatureFetcher fetcher,
            int initialReconnectDelayMillis,
            boolean streamEvenInBackground
    ) {
        this.currentContext = currentContext;
        this.dataSourceUpdateSink = dataSourceUpdateSink;
        this.fetcher = fetcher;
        this.streamUri = clientContext.getServiceEndpoints().getStreamingBaseUri();
        this.httpProperties = LDUtil.makeHttpProperties(clientContext);
        this.evaluationReasons = clientContext.isEvaluationReasons();
        this.useReport = clientContext.getHttp().isUseReport();
        this.initialReconnectDelayMillis = initialReconnectDelayMillis;
        this.streamEvenInBackground = streamEvenInBackground;
        this.diagnosticStore = ClientContextImpl.get(clientContext).getDiagnosticStore();
        this.logger = clientContext.getBaseLogger();
        executor = new BackgroundThreadExecutor().newFixedThreadPool(2);
    }

    public void start(@NonNull Callback<Boolean> resultCallback) {
        if (!running && !connection401Error) {
            logger.debug("Starting.");

            EventHandler handler = new EventHandler() {
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
                    handle(name, eventData, resultCallback);
                }

                @Override
                public void onComment(String comment) {

                }

                @Override
                public void onError(Throwable t) {
                    LDUtil.logExceptionAtErrorLevel(logger, t,
                            "Encountered EventStream error connecting to URI: {}",
                            getUri(currentContext));
                    if (t instanceof UnsuccessfulResponseException) {
                        if (diagnosticStore != null) {
                            diagnosticStore.recordStreamInit(eventSourceStarted, (int) (System.currentTimeMillis() - eventSourceStarted), true);
                        }
                        int code = ((UnsuccessfulResponseException) t).getCode();
                        if (code >= 400 && code < 500) {
                            logger.error("Encountered non-retriable error: {}. Aborting connection to stream. Verify correct Mobile Key and Stream URI", code);
                            running = false;
                            resultCallback.onError(new LDInvalidResponseCodeFailure("Unexpected Response Code From Stream Connection", t, code, false));
                            if (code == 401) {
                                connection401Error = true;
                                dataSourceUpdateSink.shutDown();
                            }
                            stop(null);
                        } else {
                            eventSourceStarted = System.currentTimeMillis();
                            resultCallback.onError(new LDInvalidResponseCodeFailure("Unexpected Response Code From Stream Connection", t, code, true));
                        }
                    } else {
                        resultCallback.onError(new LDFailure("Network error in stream connection", t, LDFailure.FailureType.NETWORK_FAILURE));
                    }
                }
            };

            EventSource.Builder builder = new EventSource.Builder(handler, getUri(currentContext));
            builder.reconnectTime(initialReconnectDelayMillis, TimeUnit.MILLISECONDS);
            builder.clientBuilderActions(new EventSource.Builder.ClientConfigurer() {
                public void configure(OkHttpClient.Builder clientBuilder) {
                    httpProperties.applyToHttpClientBuilder(clientBuilder);
                    clientBuilder.readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
            });

            builder.requestTransformer(input ->
                input.newBuilder()
                        .headers(
                                input.headers().newBuilder().addAll(httpProperties.toHeadersBuilder().build()).build()
                        ).build());

            if (useReport) {
                builder.method(METHOD_REPORT);
                builder.body(getRequestBody(currentContext));
            }

            builder.maxReconnectTime(MAX_RECONNECT_TIME_MS, TimeUnit.MILLISECONDS);

            eventSourceStarted = System.currentTimeMillis();
            es = builder.build();
            es.start();

            running = true;
        }
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
            uri = HttpHelpers.concatenateUriPath(uri, LDUtil.base64Url(context));
        }

        if (evaluationReasons) {
            uri = URI.create(uri.toString() + "?withReasons=true");
        }

        return uri;
    }

    private void handle(final String name, final String eventData,
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
                dataSourceUpdateSink.init(data.getAll());
                resultCallback.onSuccess(true);
                break;
            case PATCH:
                applyPatch(eventData, resultCallback);
                break;
            case DELETE:
                applyDelete(eventData, resultCallback);
                break;
            case PING:
                ConnectivityManager.fetchAndSetData(fetcher, currentContext, dataSourceUpdateSink,
                        LDUtil.noOpCallback(), logger);
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
        executor.execute(() -> {
            stopSync();
            if (onCompleteListener != null) {
                onCompleteListener.onSuccess(null);
            }
        });
    }

    @Override
    public boolean needsRefresh(boolean newInBackground, LDContext newEvaluationContext) {
        return !newEvaluationContext.equals(currentContext) ||
                (newInBackground && !streamEvenInBackground);
    }

    private synchronized void stopSync() {
        if (es != null) {
            es.close();
        }
        running = false;
        es = null;
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
        dataSourceUpdateSink.upsert(flag);
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
        dataSourceUpdateSink.upsert(Flag.deletedItemPlaceholder(deleteMessage.key, deleteMessage.version));
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