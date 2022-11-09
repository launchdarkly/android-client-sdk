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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.launchdarkly.sdk.android.LDConfig.JSON;
import static com.launchdarkly.sdk.android.LDUtil.isClientConnected;
import static com.launchdarkly.sdk.android.LDUtil.isHttpErrorRecoverable;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;

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
    private final int flushIntervalMillis;
    private final boolean inlineUsers;
    private ScheduledExecutorService scheduler;
    private final SummaryEventStore summaryEventStore;
    private final AtomicBoolean offline = new AtomicBoolean();
    private long currentTimeMs = System.currentTimeMillis();
    private DiagnosticStore diagnosticStore;
    private final LDLogger logger;

    DefaultEventProcessor(
            Context context,
            LDConfig config,
            SummaryEventStore summaryEventStore,
            String environmentName,
            boolean initiallyOffline,
            int capacity,
            int flushIntervalMillis,
            boolean inlineUsers,
            final DiagnosticStore diagnosticStore,
            final OkHttpClient sharedClient,
            LDLogger logger
    ) {
        this.context = context;
        this.config = config;
        this.offline.set(initiallyOffline);
        this.environmentName = environmentName;
        this.flushIntervalMillis = flushIntervalMillis;
        this.inlineUsers = inlineUsers;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.consumer = new Consumer(config);
        this.summaryEventStore = summaryEventStore;
        this.client = sharedClient;
        this.diagnosticStore = diagnosticStore;
        this.logger = logger;
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

            scheduler.scheduleAtFixedRate(consumer, flushIntervalMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    public void recordEvaluationEvent(
            LDUser user,
            String flagKey,
            int flagVersion,
            int variation,
            LDValue value,
            EvaluationReason reason,
            LDValue defaultValue,
            boolean requireFullEvent,
            Long debugEventsUntilDate
    ) {
        boolean needEvent = false, isDebug = false;
        if (requireFullEvent) {
            needEvent = true;
        } else if (debugEventsUntilDate != null) {
            long serverTimeMs = getCurrentTimeMs();
            if (debugEventsUntilDate > System.currentTimeMillis() && debugEventsUntilDate > serverTimeMs) {
                needEvent = isDebug = true;
            }
        }
        if (needEvent) {
            sendEvent(new FeatureRequestEvent(flagKey, user, value, defaultValue,
                    flagVersion < 0 ? null : Integer.valueOf(flagVersion),
                    variation < 0 ? null : Integer.valueOf(variation),
                    reason, isDebug || inlineUsers, isDebug));
        }
    }

    public void recordIdentifyEvent(
            LDUser user
    ) {
        sendEvent(new IdentifyEvent(user));
    }

    public void recordCustomEvent(
            LDUser user,
            String eventKey,
            LDValue data,
            Double metricValue
    ) {
        sendEvent(new CustomEvent(eventKey, user, data, metricValue, inlineUsers));
    }

    public void recordAliasEvent(
            LDUser user,
            LDUser previousUser
    ) {
        sendEvent(new AliasEvent(user, previousUser));
    }

    public void setOffline(boolean offline) {
        this.offline.set(offline);
    }

    private void sendEvent(Event e) {
        if (offline.get()) {
            return;
        }
        boolean processed = queue.offer(e);
        if (!processed) {
            logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
            if (diagnosticStore != null) {
                diagnosticStore.incrementDroppedEventCount();
            }
        }
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

    public void blockingFlush() {
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
            String content = config.filteredEventGson.toJson(events);
            String eventPayloadId = UUID.randomUUID().toString();
            String url = config.getEventsUri().buildUpon().appendPath("mobile").build().toString();
            HashMap<String, String> baseHeadersForRequest = new HashMap<>();
            baseHeadersForRequest.put("X-LaunchDarkly-Payload-ID", eventPayloadId);
            baseHeadersForRequest.putAll(baseEventHeaders);

            logger.debug("Posting {} event(s) to {}", events.size(), url);
            logger.debug("Events body: {}", content);

            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    logger.warn("Will retry posting events after 1 second");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {}
                }

                Request request = new Request.Builder().url(url)
                        .headers(config.headersForEnvironment(environmentName, baseHeadersForRequest))
                        .post(RequestBody.create(content, JSON))
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    logger.debug("Events Response: {}", response.code());
                    logger.debug("Events Response Date: {}", response.header("Date"));

                    if (!response.isSuccessful()) {
                        logger.warn("Unexpected response status when posting events: {}", response.code());
                        if (isHttpErrorRecoverable(response.code())) {
                            continue;
                        }
                    }

                    tryUpdateDate(response);
                    break;
                } catch (IOException e) {
                    LDUtil.logExceptionAtErrorLevel(logger, e,
                            "Unhandled exception in LaunchDarkly client attempting to connect to URI: {}",
                            request.url());
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
                    LDUtil.logExceptionAtErrorLevel(logger, pe, "Failed to parse date header");
                }
            }
        }
    }
}
