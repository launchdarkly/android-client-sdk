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
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.json.JsonSerialization;

import java.net.URI;
import java.util.concurrent.ExecutorService;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

import static com.launchdarkly.sdk.android.LDConfig.JSON;
import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

import android.app.Application;
import android.net.Uri;

class StreamUpdateProcessor {
    private static final String METHOD_REPORT = "REPORT";

    private static final String PING = "ping";
    private static final String PUT = "put";
    private static final String PATCH = "patch";
    private static final String DELETE = "delete";

    private static final long MAX_RECONNECT_TIME_MS = 3_600_000; // 1 hour

    private EventSource es;
    private final HttpProperties httpProperties;
    private final Application application;
    private final LDConfig config;
    private final ContextDataManager contextDataManager;
    private final FeatureFetcher fetcher;
    private volatile boolean running = false;
    private final Debounce queue;
    private boolean connection401Error = false;
    private final ExecutorService executor;
    private final String environmentName;
    private final LDUtil.ResultCallback<Void> notifier;
    private final DiagnosticStore diagnosticStore;
    private long eventSourceStarted;
    private final LDLogger logger;

    StreamUpdateProcessor(
            Application application,
            LDConfig config,
            ContextDataManager contextDataManager,
            FeatureFetcher fetcher,
            String environmentName,
            DiagnosticStore diagnosticStore,
            LDUtil.ResultCallback<Void> notifier,
            LDLogger logger
    ) {
        this.application = application;
        this.config = config;
        this.httpProperties = LDUtil.makeHttpProperties(config, config.getMobileKeys().get(environmentName));
        this.contextDataManager = contextDataManager;
        this.fetcher = fetcher;
        this.environmentName = environmentName;
        this.notifier = notifier;
        this.diagnosticStore = diagnosticStore;
        this.logger = logger;
        queue = new Debounce();
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
                                try {
                                    LDClient.getForMobileKey(environmentName).setOffline();
                                } catch (LaunchDarklyException e) {
                                    LDUtil.logExceptionAtErrorLevel(logger, e, "Client unavailable to be set offline");
                                }
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

            builder.maxReconnectTimeMs(MAX_RECONNECT_TIME_MS);

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
        String str = Uri.withAppendedPath(config.getStreamUri(), "meval").toString();

        if (!config.isUseReport() && context != null) {
            str += "/" + LDUtil.base64Url(context);
        }

        if (config.isEvaluationReasons()) {
            str += "?withReasons=true";
        }

        return URI.create(str);
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
                // We debounce ping requests as they trigger a separate asynchronous request for the
                // flags, overriding all flag values.
                queue.call(() -> {
                    PollingUpdater.triggerPoll(application, contextDataManager, fetcher, onCompleteListener, logger);
                    return null;
                });
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
            flag = gsonInstance().fromJson(json, Flag.class);
        } catch (Exception e) {
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