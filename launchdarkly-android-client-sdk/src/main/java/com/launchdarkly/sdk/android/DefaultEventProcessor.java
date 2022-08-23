package com.launchdarkly.sdk.android;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.io.Closeable;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.launchdarkly.sdk.android.LDConfig.JSON;
import static com.launchdarkly.sdk.android.LDUtil.isClientConnected;
import static com.launchdarkly.sdk.android.LDUtil.isHttpErrorRecoverable;

class DefaultEventProcessor implements EventProcessor, Closeable {
    private static final HashMap<String, String> baseEventHeaders = new HashMap<String, String>() {{
        put("Content-Type", "application/json");
        put("X-LaunchDarkly-Event-Schema", "3");
    }};

    private final BlockingQueue<Event> queue;
    private final Consumer consumer;
    private final OkHttpClient client;
    private final Context context;
    private final LDConfig config;
    private final String environmentName;
    private ScheduledExecutorService scheduler;
    private final SummaryEventStore summaryEventStore;
    private long currentTimeMs = System.currentTimeMillis();
    private DiagnosticStore diagnosticStore;

    DefaultEventProcessor(Context context, LDConfig config, SummaryEventStore summaryEventStore, String environmentName,
                          final DiagnosticStore diagnosticStore, final OkHttpClient sharedClient) {
        this.context = context;
        this.config = config;
        this.environmentName = environmentName;
        this.queue = new ArrayBlockingQueue<>(config.getEventsCapacity());
        this.consumer = new Consumer(config);
        this.summaryEventStore = summaryEventStore;
        this.client = sharedClient;
        this.diagnosticStore = diagnosticStore;
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
        if (scheduler != null) {
            scheduler.schedule(consumer, 0, TimeUnit.MILLISECONDS);
        }
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
                long eventsInBatch = queue.drainTo(events);
                if (diagnosticStore != null) {
                    diagnosticStore.recordEventsInLastBatch(eventsInBatch);
                }
                SummaryEvent summaryEvent = summaryEventStore.getSummaryEventAndClear();

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
            String url = config.getEventsUri().buildUpon().appendPath("mobile").build().toString();
            HashMap<String, String> baseHeadersForRequest = new HashMap<>();
            baseHeadersForRequest.put("X-LaunchDarkly-Payload-ID", eventPayloadId);
            baseHeadersForRequest.putAll(baseEventHeaders);

            LDConfig.log().d("Posting %s event(s) to %s", events.size(), url);
            LDConfig.log().d("Events body: %s", content);

            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    LDConfig.log().w("Will retry posting events after 1 second");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {}
                }

                Request request = new Request.Builder().url(url)
                        .headers(config.headersForEnvironment(environmentName, baseHeadersForRequest))
                        .post(RequestBody.create(content, JSON))
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    LDConfig.log().d("Events Response: %s", response.code());
                    LDConfig.log().d("Events Response Date: %s", response.header("Date"));

                    if (!response.isSuccessful()) {
                        LDConfig.log().w("Unexpected response status when posting events: %d", response.code());
                        if (isHttpErrorRecoverable(response.code())) {
                            continue;
                        }
                    }

                    tryUpdateDate(response);
                    break;
                } catch (IOException e) {
                    LDConfig.log().e(e, "Unhandled exception in LaunchDarkly client attempting to connect to URI: %s", request.url());
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
                    LDConfig.log().e(pe, "Failed to parse date header");
                }
            }
        }
    }
}
