package com.launchdarkly.sdk.android;

import android.os.Build;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.UserAttribute;
import com.launchdarkly.sdk.internal.events.DefaultEventSender;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.EventsConfiguration;
import com.launchdarkly.sdk.internal.http.HttpProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

abstract class EventUtil {
    private static final String ANALYTICS_EVENTS_REQUEST_PATH = "/mobile/events/bulk";
    private static final String DIAGNOSTIC_EVENTS_REQUEST_PATH = "/mobile/events/diagnostic";

    // Constructs the EventsConfiguration parameters used by components in java-sdk-internal.
    static EventsConfiguration makeEventsConfiguration(
            LDConfig config,
            HttpProperties httpProperties,
            boolean initiallyInBackground,
            LDLogger logger
    ) {
        List<AttributeRef> privateAttributes = new ArrayList<>();
        if (config.getPrivateAttributes() != null) {
            for (UserAttribute a: config.getPrivateAttributes()) {
                privateAttributes.add(AttributeRef.fromLiteral(a.getName()));
            }
        }
        return new EventsConfiguration(
                config.allAttributesPrivate(),
                config.getEventsCapacity(),
                null, // contextDeduplicator - not needed for client-side use
                config.getDiagnosticRecordingIntervalMillis(),
                null, // TODO: diagnosticStore
                new DefaultEventSender(
                        httpProperties,
                        ANALYTICS_EVENTS_REQUEST_PATH,
                        DIAGNOSTIC_EVENTS_REQUEST_PATH,
                        0, // use default retry delay
                        logger),
                1, // eventSendingThreadPoolSize
                URI.create(config.getEventsUri().toString()),
                config.getEventsFlushIntervalMillis(),
                initiallyInBackground, // initiallyInBackground
                true, // initiallyOffline
                privateAttributes
        );
        // Note, we are always setting initiallyOffline to true because ConnectivityManager will
        // tell the DefaultEventProcessor when it is OK to go online.
    }

    static ScheduledExecutorService makeEventsTaskExecutor() {
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
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
    }

    static DiagnosticStore.SdkDiagnosticParams makeDiagnosticParams(LDConfig config, String mobileKey) {
        LDValue configProperties = LDValue.buildObject()
                .put("customBaseURI", !LDConfig.DEFAULT_POLL_URI.equals(config.getPollUri()))
                .put("customEventsURI", !LDConfig.DEFAULT_EVENTS_URI.equals(config.getEventsUri()))
                .put("customStreamURI", !LDConfig.DEFAULT_STREAM_URI.equals(config.getStreamUri()))
                .put("eventsCapacity", config.getEventsCapacity())
                .put("connectTimeoutMillis", config.getConnectionTimeoutMillis())
                .put("eventsFlushIntervalMillis", config.getEventsFlushIntervalMillis())
                .put("streamingDisabled", !config.isStream())
                .put("allAttributesPrivate", config.allAttributesPrivate())
                .put("pollingIntervalMillis", config.getPollingIntervalMillis())
                .put("backgroundPollingIntervalMillis", config.getBackgroundPollingIntervalMillis())
                .put("useReport", config.isUseReport())
                .put("backgroundPollingDisabled", config.isDisableBackgroundPolling())
                .put("evaluationReasonsRequested", config.isEvaluationReasons())
                .put("mobileKeyCount", config.getMobileKeys().size())
                .put("diagnosticRecordingIntervalMillis", config.getDiagnosticRecordingIntervalMillis())
                .put("maxCachedUsers", config.getMaxCachedContexts())
                .build();
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, String> kv: LDUtil.makeHttpProperties(config, mobileKey).getDefaultHeaders()) {
            headers.put(kv.getKey(), kv.getValue());
        }
        return new DiagnosticStore.SdkDiagnosticParams(
                mobileKey,
                "android-client-sdk",
                BuildConfig.VERSION_NAME,
                "Android",
                LDValue.buildObject().put("androidSDKVersion", Build.VERSION.SDK_INT).build(),
                headers,
                Collections.singletonList(configProperties)
        );
    }
}
