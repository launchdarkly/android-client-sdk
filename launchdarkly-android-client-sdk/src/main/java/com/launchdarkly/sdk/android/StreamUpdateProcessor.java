package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import okhttp3.RequestBody;

import static com.launchdarkly.sdk.android.LDConfig.GSON;
import static com.launchdarkly.sdk.android.LDConfig.JSON;

import android.net.Uri;

class StreamUpdateProcessor {
    private static final String METHOD_REPORT = "REPORT";

    private static final String PING = "ping";
    private static final String PUT = "put";
    private static final String PATCH = "patch";
    private static final String DELETE = "delete";

    private static final long MAX_RECONNECT_TIME_MS = 3_600_000; // 1 hour

    private EventSource es;
    private final DataSource dataSourceConfig;
    private final HttpConfiguration httpConfig;
    private final LDConfig config;
    private final UserManager userManager;
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
            LDConfig config,
            DataSource dataSourceConfig,
            HttpConfiguration httpConfig,
            UserManager userManager,
            String environmentName,
            DiagnosticStore diagnosticStore,
            LDUtil.ResultCallback<Void> notifier,
            LDLogger logger
    ) {
        this.config = config;
        this.dataSourceConfig = dataSourceConfig;
        this.httpConfig = httpConfig;
        this.userManager = userManager;
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
                        diagnosticStore.addStreamInit(eventSourceStarted, (int) (System.currentTimeMillis() - eventSourceStarted), false);
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
                            getUri(userManager.getCurrentUser()));
                    if (t instanceof UnsuccessfulResponseException) {
                        if (diagnosticStore != null) {
                            diagnosticStore.addStreamInit(eventSourceStarted, (int) (System.currentTimeMillis() - eventSourceStarted), true);
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

            EventSource.Builder builder = new EventSource.Builder(handler, getUri(userManager.getCurrentUser()));
            builder.connectTimeoutMs(httpConfig.getConnectTimeoutMillis());
            builder.reconnectTimeMs(dataSourceConfig.getInitialReconnectDelayMillis());

            builder.requestTransformer(input -> {
                Map<String, List<String>> esHeaders = input.headers().toMultimap();
                HashMap<String, String> collapsed = new HashMap<>();
                for (Map.Entry<String, List<String>> entry: esHeaders.entrySet()) {
                    for (String headerVal: entry.getValue()) {
                        collapsed.put(entry.getKey(), headerVal);
                        // We never provide multiple values for a header so we collapse to just
                        // the first entry in the multimap.
                        break;
                    }
                }
                return input.newBuilder().headers(
                        LDUtil.makeRequestHeaders(httpConfig, collapsed)
                ).build();
            });

            if (httpConfig.isUseReport()) {
                builder.method(METHOD_REPORT);
                builder.body(getRequestBody(userManager.getCurrentUser()));
            }

            builder.maxReconnectTimeMs(MAX_RECONNECT_TIME_MS);

            eventSourceStarted = System.currentTimeMillis();
            es = builder.build();
            es.start();

            running = true;
        }
    }

    @NonNull
    private RequestBody getRequestBody(@Nullable LDUser user) {
        logger.debug("Attempting to report user in stream");
        return RequestBody.create(GSON.toJson(user), JSON);
    }

    private URI getUri(@Nullable LDUser user) {
        String str = Uri.withAppendedPath(config.getStreamUri(), "meval").toString();

        if (!httpConfig.isUseReport() && user != null) {
            str += "/" + DefaultUserManager.base64Url(user);
        }

        if (config.isEvaluationReasons()) {
            str += "?withReasons=true";
        }

        return URI.create(str);
    }

    private void handle(final String name, final String eventData,
                        @NonNull final LDUtil.ResultCallback<Void> onCompleteListener) {
        switch (name.toLowerCase()) {
            case PUT: userManager.putCurrentUserFlags(eventData, onCompleteListener); break;
            case PATCH: userManager.patchCurrentUserFlags(eventData, onCompleteListener); break;
            case DELETE: userManager.deleteCurrentUserFlag(eventData, onCompleteListener); break;
            case PING:
                // We debounce ping requests as they trigger a separate asynchronous request for the
                // flags, overriding all flag values.
                queue.call(() -> {
                    userManager.updateCurrentUser(onCompleteListener);
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
}