package com.launchdarkly.android;

import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import java.io.Closeable;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import timber.log.Timber;

import static com.launchdarkly.android.LDConfig.JSON;
import static com.launchdarkly.android.Util.isClientConnected;
import static com.launchdarkly.android.Util.isHttpErrorRecoverable;

class DefaultEventProcessor implements EventProcessor, Closeable {
    private final BlockingQueue<Event> queue;
    private final Consumer consumer;
    private final OkHttpClient client;
    private final Context context;
    private final LDConfig config;
    private final String environmentName;
    private ScheduledExecutorService scheduler;
    private final SummaryEventSharedPreferences summaryEventSharedPreferences;
    private long currentTimeMs = System.currentTimeMillis();

    DefaultEventProcessor(Context context, LDConfig config, SummaryEventSharedPreferences summaryEventSharedPreferences, String environmentName) {
        this.context = context;
        this.config = config;
        this.environmentName = environmentName;
        this.queue = new ArrayBlockingQueue<>(config.getEventsCapacity());
        this.consumer = new Consumer(config);
        this.summaryEventSharedPreferences = summaryEventSharedPreferences;

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(1, config.getEventsFlushIntervalMillis() * 2, TimeUnit.MILLISECONDS))
                .connectTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(new SSLHandshakeInterceptor());

        if (Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 22) {
            try {
                builder.sslSocketFactory(new ModernTLSSocketFactory(), TLSUtils.defaultTrustManager());
            } catch (GeneralSecurityException ignored) {
                // TLS is not available, so don't set up the socket factory, swallow the exception
            }
        }

        client = builder.build();
    }

    public void start() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                final AtomicLong count = new AtomicLong(0);

                @Override
                public Thread newThread(@NonNull Runnable r) {
                    Thread thread = Executors.defaultThreadFactory().newThread(r);
                    thread.setName(String.format(Locale.ROOT, "LaunchDarkly-DefaultEventProcessor-%d",
                            count.getAndIncrement()));
                    thread.setDaemon(true);
                    return thread;
                }
            });

            scheduler.scheduleAtFixedRate(consumer, config.getEventsFlushIntervalMillis(), config.getEventsFlushIntervalMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    public boolean sendEvent(Event e) {
        return queue.offer(e);
    }

    @Override
    public void close() {
        stop();
        flush();
    }

    public void flush() {
        Executors.newSingleThreadExecutor().execute(consumer);
    }

    @VisibleForTesting
    void blockingFlush() {
        consumer.run();
    }

    public long getCurrentTimeMs() {
        return currentTimeMs;
    }

    class Consumer implements Runnable {
        private final LDConfig config;

        Consumer(LDConfig config) {
            this.config = config;
        }

        @Override
        public void run() {
            flush();
        }

        synchronized void flush() {
            if (isClientConnected(context, environmentName)) {
                List<Event> events = new ArrayList<>(queue.size() + 1);
                queue.drainTo(events);
                SummaryEvent summaryEvent = summaryEventSharedPreferences.getSummaryEventAndClear();

                if (summaryEvent != null) {
                    events.add(summaryEvent);
                }

                if (!events.isEmpty()) {
                    postEvents(events);
                }
            }
        }

        private void postEvents(List<Event> events) {
            String content = config.getFilteredEventGson().toJson(events);
            String eventPayloadId = UUID.randomUUID().toString();
            String url = config.getEventsUri().toString();

            Timber.d("Posting %s event(s) to %s", events.size(), url);

            Timber.d("Events body: %s", content);

            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    Timber.w("Will retry posting events after 1 second");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {}
                }

                Request request = config.getRequestBuilderFor(environmentName)
                        .url(url)
                        .post(RequestBody.create(JSON, content))
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-LaunchDarkly-Event-Schema", "3")
                        .addHeader("X-LaunchDarkly-Payload-ID", eventPayloadId)
                        .build();

                Response response = null;
                try {
                    response = client.newCall(request).execute();
                    Timber.d("Events Response: %s", response.code());
                    Timber.d("Events Response Date: %s", response.header("Date"));

                    if (!response.isSuccessful()) {
                        Timber.w("Unexpected response status when posting events: %d", response.code());
                        if (isHttpErrorRecoverable(response.code())) {
                            continue;
                        }
                    }

                    tryUpdateDate(response);
                    break;
                } catch (IOException e) {
                    Timber.e(e, "Unhandled exception in LaunchDarkly client attempting to connect to URI: %s", request.url());
                } finally {
                    if (response != null) response.close();
                }
            }
        }

        private void tryUpdateDate(Response response) {
            String dateString = response.header("Date");
            if (dateString != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                try {
                    Date date = sdf.parse(dateString);
                    currentTimeMs = date.getTime();
                } catch (ParseException pe) {
                    Timber.e(pe, "Failed to parse date header");
                }
            }
        }
    }
}
