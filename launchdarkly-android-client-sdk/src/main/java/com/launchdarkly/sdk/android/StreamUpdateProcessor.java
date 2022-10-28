package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
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

class StreamUpdateProcessor {
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
    private final HttpProperties httpProperties;
    private final LDConfig config;
    private final URI streamUri;
    private final ContextDataManager contextDataManager;
    private volatile boolean running = false;
    private boolean connection401Error = false;
    private final ExecutorService executor;
    private final ConnectivityManager.DataSourceActions dataSourceActions;
    private final LDUtil.ResultCallback<Void> notifier;
    private final DiagnosticStore diagnosticStore;
    private long eventSourceStarted;
    private final LDLogger logger;

    StreamUpdateProcessor(
            @NonNull ClientState clientState,
            @NonNull LDConfig config,
            @NonNull ContextDataManager contextDataManager,
            @NonNull ConnectivityManager.DataSourceActions dataSourceActions,
            @Nullable DiagnosticStore diagnosticStore,
            @NonNull LDUtil.ResultCallback<Void> notifier
    ) {
        this.config = config;
        this.streamUri = config.serviceEndpoints.getStreamingBaseUri();
        this.httpProperties = LDUtil.makeHttpProperties(config, clientState.getMobileKey());
        this.contextDataManager = contextDataManager;
        this.dataSourceActions = dataSourceActions;
        this.notifier = notifier;
        this.diagnosticStore = diagnosticStore;
        this.logger = clientState.getLogger();
        executor = new BackgroundThreadExecutor().newFixedThreadPool(2);
    }

    synchronized void start() {
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
                    handle(name, eventData, notifier);
                }

                @Override
                public void onComment(String comment) {

                }

                @Override
                public void onError(Throwable t) {
                    LDUtil.logExceptionAtErrorLevel(logger, t,
                            "Encountered EventStream error connecting to URI: {}",
                            getUri(contextDataManager.getCurrentContext()));
                    if (t instanceof UnsuccessfulResponseException) {
                        if (diagnosticStore != null) {
                            diagnosticStore.recordStreamInit(eventSourceStarted, (int) (System.currentTimeMillis() - eventSourceStarted), true);
                        }
                        int code = ((UnsuccessfulResponseException) t).getCode();
                        if (code >= 400 && code < 500) {
                            logger.error("Encountered non-retriable error: {}. Aborting connection to stream. Verify correct Mobile Key and Stream URI", code);
                            running = false;
                            notifier.onError(new LDInvalidResponseCodeFailure("Unexpected Response Code From Stream Connection", t, code, false));
                            if (code == 401) {
                                connection401Error = true;
                                dataSourceActions.shutdownForInvalidMobileKey();
                            }
                            stop(null);
                        } else {
                            eventSourceStarted = System.currentTimeMillis();
                            notifier.onError(new LDInvalidResponseCodeFailure("Unexpected Response Code From Stream Connection", t, code, true));
                        }
                    } else {
                        notifier.onError(new LDFailure("Network error in stream connection", t, LDFailure.FailureType.NETWORK_FAILURE));
                    }
                }
            };

            EventSource.Builder builder = new EventSource.Builder(handler, getUri(contextDataManager.getCurrentContext()));
            builder.clientBuilderActions(new EventSource.Builder.ClientConfigurer() {
                public void configure(OkHttpClient.Builder clientBuilder) {
                    httpProperties.applyToHttpClientBuilder(clientBuilder);
                    clientBuilder.readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
            });

            builder.requestTransformer(input -> {
                return input.newBuilder()
                        .headers(
                                input.headers().newBuilder().addAll(httpProperties.toHeadersBuilder().build()).build()
                        ).build();
            });

            if (config.isUseReport()) {
                builder.method(METHOD_REPORT);
                builder.body(getRequestBody(contextDataManager.getCurrentContext()));
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

        if (!config.isUseReport() && context != null) {
            uri = HttpHelpers.concatenateUriPath(uri, LDUtil.base64Url(context));
        }

        if (config.isEvaluationReasons()) {
            uri = URI.create(uri.toString() + "?withReasons=true");
        }

        return uri;
    }

    private void handle(final String name, final String eventData,
                        @NonNull final LDUtil.ResultCallback<Void> onCompleteListener) {
        switch (name.toLowerCase()) {
            case PUT:
                contextDataManager.initDataFromJson(contextDataManager.getCurrentContext(),
                        eventData, onCompleteListener);
                break;
            case PATCH:
                applyPatch(eventData, onCompleteListener);
                break;
            case DELETE:
                applyDelete(eventData, onCompleteListener);
                break;
            case PING:
                dataSourceActions.triggerPoll();
                break;
            default:
                logger.debug("Found an unknown stream protocol: {}", name);
                onCompleteListener.onError(new LDFailure("Unknown Stream Element Type", null, LDFailure.FailureType.UNEXPECTED_STREAM_ELEMENT_TYPE));
        }
    }

    void stop(final LDUtil.ResultCallback<Void> onCompleteListener) {
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

    private synchronized void stopSync() {
        if (es != null) {
            es.close();
        }
        running = false;
        es = null;
        logger.debug("Stopped.");
    }

    private void applyPatch(String json, @NonNull final LDUtil.ResultCallback<Void> onCompleteListener) {
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
        contextDataManager.upsert(flag);
        onCompleteListener.onSuccess(null);
    }

    private void applyDelete(String json, @NonNull final LDUtil.ResultCallback<Void> onCompleteListener) {
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
        contextDataManager.upsert(Flag.deletedItemPlaceholder(deleteMessage.key, deleteMessage.version));
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