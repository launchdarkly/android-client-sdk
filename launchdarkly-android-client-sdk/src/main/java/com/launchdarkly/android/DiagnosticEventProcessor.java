package com.launchdarkly.android;

import androidx.annotation.NonNull;
import android.content.Context;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.launchdarkly.android.LDConfig.JSON;

class DiagnosticEventProcessor {
    private final OkHttpClient client;
    private final LDConfig config;
    private final String environment;
    private final DiagnosticStore diagnosticStore;
    private final ThreadFactory diagnosticThreadFactory;
    private final Context context;
    private ScheduledExecutorService executorService;

    DiagnosticEventProcessor(LDConfig config, String environment, final DiagnosticStore diagnosticStore, Context context,
                             OkHttpClient sharedClient) {
        this.config = config;
        this.environment = environment;
        this.diagnosticStore = diagnosticStore;
        this.client = sharedClient;
        this.context = context;

        diagnosticThreadFactory = new ThreadFactory() {
            final AtomicLong count = new AtomicLong(0);

            @Override
            public Thread newThread(@NonNull Runnable r) {
                Thread thread = Executors.defaultThreadFactory().newThread(r);
                thread.setName(String.format(Locale.ROOT, "LaunchDarkly-DiagnosticEventProcessor-%d",
                        count.getAndIncrement()));
                thread.setDaemon(true);
                return thread;
            }
        };

        if (Foreground.get().isForeground()) {
            startScheduler();
            DiagnosticEvent.Statistics lastStats = diagnosticStore.getLastStats();
            if (lastStats != null) {
                sendDiagnosticEventAsync(lastStats);
            }

            if (diagnosticStore.isNewId()) {
                sendDiagnosticEventAsync(new DiagnosticEvent.Init(System.currentTimeMillis(), diagnosticStore.getDiagnosticId(), config));
            }
        }

        Foreground.get().addListener(new Foreground.Listener() {
            @Override
            public void onBecameForeground() {
                startScheduler();
            }

            @Override
            public void onBecameBackground() {
                stopScheduler();
            }
        });
    }

    private void enqueueEvent() {
        if (!LDUtil.isInternetConnected(context)) {
            // if we're not connected to the internet then dont try and send an event
            return;
        }

        sendDiagnosticEventSync(diagnosticStore.getCurrentStatsAndReset());
    }

    void startScheduler() {
        long initialDelay = config.getDiagnosticRecordingIntervalMillis() - (System.currentTimeMillis() - diagnosticStore.getDataSince());
        long safeDelay = Math.min(Math.max(initialDelay, 0), config.getDiagnosticRecordingIntervalMillis());

        executorService = Executors.newSingleThreadScheduledExecutor(diagnosticThreadFactory);
        executorService.scheduleAtFixedRate(
            this::enqueueEvent,
            safeDelay, 
            config.getDiagnosticRecordingIntervalMillis(), 
            TimeUnit.MILLISECONDS
        );
    }

    void stopScheduler() {
        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }
    }

    void close() {
        stopScheduler();
    }

    private void sendDiagnosticEventAsync(final DiagnosticEvent diagnosticEvent) {
        executorService.submit(() -> sendDiagnosticEventSync(diagnosticEvent));
    }

    void sendDiagnosticEventSync(DiagnosticEvent diagnosticEvent) {
        String content = GsonCache.getGson().toJson(diagnosticEvent);
        Request.Builder requestBuilder = config.getRequestBuilderFor(environment)
                .url(config.getEventsUri().buildUpon().appendEncodedPath("mobile/events/diagnostic").build().toString())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(content, JSON));

        Request request = config.buildRequestWithAdditionalHeaders(requestBuilder);
        LDConfig.LOG.d("Posting diagnostic event to %s with body %s", request.url(), content);

        Response response = null;
        try {
            response = client.newCall(request).execute();
            LDConfig.LOG.d("Diagnostic Event Response: %s", response.code());
            LDConfig.LOG.d("Diagnostic Event Response Date: %s", response.header("Date"));
        } catch (IOException e) {
            LDConfig.LOG.w(e, "Unhandled exception in LaunchDarkly client attempting to connect to URI: %s", request.url());
        } finally {
            if (response != null) response.close();
        }
    }
}
