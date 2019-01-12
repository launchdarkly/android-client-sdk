package com.launchdarkly.android;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;

import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import timber.log.Timber;

import static com.launchdarkly.android.LDConfig.GSON;

class StreamUpdateProcessor implements UpdateProcessor {

    private static final String METHOD_REPORT = "REPORT";

    private static final String PING = "ping";
    private static final String PUT = "put";
    private static final String PATCH = "patch";
    private static final String DELETE = "delete";

    private static final long MAX_RECONNECT_TIME_MS = 3600000; // 1 hour

    private EventSource es;
    private final LDConfig config;
    private final UserManager userManager;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile boolean running = false;
    private SettableFuture<Void> initFuture;
    private Debounce queue;
    private boolean connection401Error = false;
    private final ExecutorService executor;

    StreamUpdateProcessor(LDConfig config, UserManager userManager) {
        this.config = config;
        this.userManager = userManager;
        queue = new Debounce();

        executor = new BackgroundThreadExecutor().newFixedThreadPool(2);
    }

    public synchronized ListenableFuture<Void> start() {
        if (!running && !connection401Error) {
            initFuture = SettableFuture.create();
            initialized.set(false);
            stop();
            Timber.d("Starting.");
            Headers headers = new Headers.Builder()
                    .add("Authorization", LDConfig.AUTH_SCHEME + config.getMobileKey())
                    .add("User-Agent", LDConfig.USER_AGENT_HEADER_VALUE)
                    .add("Accept", "text/event-stream")
                    .build();

            EventHandler handler = new EventHandler() {
                @Override
                public void onOpen() throws Exception {
                    Timber.i("Started LaunchDarkly EventStream");
                }

                @Override
                public void onClosed() throws Exception {
                    Timber.i("Closed LaunchDarkly EventStream");
                }

                @Override
                public void onMessage(final String name, MessageEvent event) throws Exception {
                    Timber.d("onMessage: name: %s", name);
                    final String eventData = event.getData();
                    Callable<Void> updateCurrentUserFunction = new Callable<Void>() {
                        @Override
                        public Void call() throws Exception {
                            Timber.d("consumeThis: event: %s", eventData);
                            if (!initialized.getAndSet(true)) {
                                initFuture.setFuture(handle(name, eventData));
                                Timber.i("Initialized LaunchDarkly streaming connection");
                            } else {
                                handle(name, eventData);
                            }
                            return null;
                        }
                    };

                    queue.call(updateCurrentUserFunction);
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
                            if (!initialized.getAndSet(true)) {
                                initFuture.setException(t);
                            }
                            if (code == 401) {
                                connection401Error = true;
                                try {
                                    LDClient clientSingleton = LDClient.get();
                                    clientSingleton.setOffline();
                                } catch (LaunchDarklyException e) {
                                    Timber.e(e, "Client unavailable to be set offline");
                                }
                            }
                            stop();
                        }
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
        return initFuture;
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

        return URI.create(str);
    }

    private ListenableFuture<Void> handle(String name, String eventData) {
        switch (name.toLowerCase()) {
            case PING:
                return userManager.updateCurrentUser();
            case PUT:
                return userManager.putCurrentUserFlags(eventData);
            case PATCH:
                return userManager.patchCurrentUserFlags(eventData);
            case DELETE:
                return userManager.deleteCurrentUserFlag(eventData);
            default:
                Timber.d("Found an unknown stream protocol: %s", name);
                return SettableFuture.create();
        }
    }

    public synchronized void stop() {
        Timber.d("Stopping.");
        if (es != null) {
            // We do this in a separate thread because closing the stream involves a network operation and we don't want to do a network operation on the main thread.
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    stopSync();
                }
            });
        }
    }

    private synchronized void stopSync() {
        if (es != null) {
            es.close();
        }
        running = false;
        es = null;
        Timber.d("Stopped.");
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    @Override
    public synchronized ListenableFuture<Void> restart() {
        final SettableFuture<Void> returnFuture = SettableFuture.create();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                StreamUpdateProcessor.this.stopSync();
                returnFuture.setFuture(StreamUpdateProcessor.this.start());
            }
        });
        return returnFuture;
    }

}
