package com.launchdarkly.android;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;

import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import timber.log.Timber;

import static com.launchdarkly.android.LDConfig.GSON;

class StreamUpdateProcessor {

    private static final String METHOD_REPORT = "REPORT";

    private static final String PING = "ping";
    private static final String PUT = "put";
    private static final String PATCH = "patch";
    private static final String DELETE = "delete";

    private static final long MAX_RECONNECT_TIME_MS = 3_600_000; // 1 hour

    private EventSource es;
    private final LDConfig config;
    private final UserManager userManager;
    private volatile boolean running = false;
    @SuppressWarnings("deprecation")
    private final Debounce queue;
    private boolean connection401Error = false;
    private final ExecutorService executor;
    private final String environmentName;
    private final LDUtil.ResultCallback<Void> notifier;

    StreamUpdateProcessor(LDConfig config, UserManager userManager, String environmentName, LDUtil.ResultCallback<Void> notifier) {
        this.config = config;
        this.userManager = userManager;
        this.environmentName = environmentName;
        this.notifier = notifier;
        //noinspection deprecation
        queue = new Debounce();
        executor = new BackgroundThreadExecutor().newFixedThreadPool(2);
    }

    synchronized void start() {
        if (!running && !connection401Error) {
            Timber.d("Starting.");
            Headers headers = new Headers.Builder()
                    .add("Authorization", LDConfig.AUTH_SCHEME + config.getMobileKeys().get(environmentName))
                    .add("User-Agent", LDConfig.USER_AGENT_HEADER_VALUE)
                    .add("Accept", "text/event-stream")
                    .build();

            EventHandler handler = new EventHandler() {
                @Override
                public void onOpen() {
                    Timber.i("Started LaunchDarkly EventStream");
                }

                @Override
                public void onClosed() {
                    Timber.i("Closed LaunchDarkly EventStream");
                }

                @Override
                public void onMessage(final String name, MessageEvent event) {
                    final String eventData = event.getData();
                    Timber.d("onMessage: %s: %s", name, eventData);
                    handle(name, eventData, notifier);
                }

                @Override
                public void onComment(String comment) {

                }

                @Override
                public void onError(Throwable t) {
                    Timber.e(t, "Encountered EventStream error connecting to URI: %s", getUri(userManager.getCurrentUser()));
                    if (t instanceof UnsuccessfulResponseException) {
                        int code = ((UnsuccessfulResponseException) t).getCode();
                        if (code >= 400 && code < 500) {
                            Timber.e("Encountered non-retriable error: %s. Aborting connection to stream. Verify correct Mobile Key and Stream URI", code);
                            running = false;
                            notifier.onError(new LDInvalidResponseCodeFailure("Unexpected Response Code From Stream Connection", t, code, false));
                            if (code == 401) {
                                connection401Error = true;
                                try {
                                    LDClient.getForMobileKey(environmentName).setOffline();
                                } catch (LaunchDarklyException e) {
                                    Timber.e(e, "Client unavailable to be set offline");
                                }
                            }
                            stop(null);
                        } else {
                            notifier.onError(new LDInvalidResponseCodeFailure("Unexpected Response Code From Stream Connection", t, code, true));
                        }
                    } else {
                        notifier.onError(new LDFailure("Network error in stream connection", t, LDFailure.FailureType.NETWORK_FAILURE));
                    }
                }
            };

            EventSource.Builder builder = new EventSource.Builder(handler, getUri(userManager.getCurrentUser()))
                    .headers(headers);

            if (config.isUseReport()) {
                builder.method(METHOD_REPORT);
                builder.body(getRequestBody(userManager.getCurrentUser()));
            }

            builder.maxReconnectTimeMs(MAX_RECONNECT_TIME_MS);

            es = builder.build();
            es.start();

            running = true;
        }
    }

    @NonNull
    private RequestBody getRequestBody(@Nullable LDUser user) {
        Timber.d("Attempting to report user in stream");
        return RequestBody.create(MediaType.parse("application/json;charset=UTF-8"), GSON.toJson(user));
    }

    private URI getUri(@Nullable LDUser user) {
        String str = config.getStreamUri().toString() + "/meval";

        if (!config.isUseReport() && user != null) {
            str += "/" + user.getAsUrlSafeBase64();
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
                Callable<Void> pingDebounceFunction = new Callable<Void>() {
                    @Override
                    public Void call() {
                        userManager.updateCurrentUser(onCompleteListener);
                        return null;
                    }
                };
                queue.call(pingDebounceFunction);
                break;
            default:
                Timber.d("Found an unknown stream protocol: %s", name);
                onCompleteListener.onError(new LDFailure("Unknown Stream Element Type", null, LDFailure.FailureType.UNEXPECTED_STREAM_ELEMENT_TYPE));
        }
    }

    synchronized void stop(final LDUtil.ResultCallback<Void> onCompleteListener) {
        Timber.d("Stopping.");
        // We do this in a separate thread because closing the stream involves a network
        // operation and we don't want to do a network operation on the main thread.
        executor.execute(new Runnable() {
            @Override
            public void run() {
                stopSync();
                if (onCompleteListener != null) {
                    onCompleteListener.onSuccess(null);
                }
            }
        });
    }

    private synchronized void stopSync() {
        if (es != null) {
            es.close();
        }
        running = false;
        es = null;
        Timber.d("Stopped.");
    }
}