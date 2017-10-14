package com.launchdarkly.android;


import android.util.Log;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Headers;

class StreamUpdateProcessor implements UpdateProcessor {
    private static final String TAG = "LDStreamProcessor";

    private EventSource es;
    private final LDConfig config;
    private final UserManager userManager;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile boolean running = false;
    private final URI uri;
    private SettableFuture<Void> initFuture;
    private Debounce queue;

    StreamUpdateProcessor(LDConfig config, UserManager userManager) {
        this.config = config;
        this.userManager = userManager;
        uri = URI.create(config.getStreamUri().toString() + "/mping");
        queue = new Debounce();
    }

    public synchronized ListenableFuture<Void> start() {
        initFuture = SettableFuture.create();
        initialized.set(false);

        if (!running) {
            stop();
            Log.d(TAG, "Starting.");
            Headers headers = new Headers.Builder()
                    .add("Authorization", config.getMobileKey())
                    .add("User-Agent", LDConfig.USER_AGENT_HEADER_VALUE)
                    .add("Accept", "text/event-stream")
                    .build();

            EventHandler handler = new EventHandler() {
                @Override
                public void onOpen() throws Exception {
                    Log.i(TAG, "Started LaunchDarkly EventStream");
                }

                @Override
                public void onClosed() throws Exception {
                    Log.i(TAG, "Closed LaunchDarkly EventStream");
                }

                @Override
                public void onMessage(String name, MessageEvent event) throws Exception {
                    Log.d(TAG, "onMessage: name: " + name);
                    final String eventData = event.getData();
                    Callable<Void> updateCurrentUserFunction = new Callable<Void>() {
                        @Override
                        public Void call() throws Exception {
                            Log.d(TAG, "consumeThis: event: " + eventData);
                            if (!initialized.getAndSet(true)) {
                                initFuture.setFuture(userManager.updateCurrentUser());
                                Log.i(TAG, "Initialized LaunchDarkly streaming connection");
                            } else {
                                userManager.updateCurrentUser();
                            }
                            return null;
                        }
                    };

                    queue.call(updateCurrentUserFunction);
                }

                @Override
                public void onComment(String comment) throws Exception {

                }

                @Override
                public void onError(Throwable t) {
                    Log.e(TAG, "Encountered EventStream error connecting to URI: " + uri, t);
                    if (t instanceof UnsuccessfulResponseException) {
                        int code = ((UnsuccessfulResponseException) t).getCode();
                        if (code >= 400 && code < 500) {
                            Log.e(TAG, "Encountered non-retriable error: " + code + ". Aborting connection to stream. Verify correct Mobile Key and Stream URI");
                            running = false;
                            if (!initialized.getAndSet(true)) {
                                initFuture.setException(t);
                            }
                            stop();
                        }
                    }
                }
            };

            es = new EventSource.Builder(handler, uri)
                    .headers(headers)
                    .build();
            es.start();
            running = true;
        }
        return initFuture;
    }

    public synchronized void stop() {
        Log.d(TAG, "Stopping.");
        if (es != null) {
            // We do this in a separate thread because closing the stream involves a network operation and we don't want to do a network operation on the main thread.
            new Thread(
                    new Runnable() {
                        @Override
                        public void run() {
                            // Moves the current Thread into the background
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                            stopSync();
                        }
                    }).start();
        }
    }

    private synchronized void stopSync() {
        if (es != null) {
            es.close();
        }
        running = false;
        es = null;
        Log.d(TAG, "Stopped.");
    }

    public boolean isInitialized() {
        return initialized.get();
    }
}
