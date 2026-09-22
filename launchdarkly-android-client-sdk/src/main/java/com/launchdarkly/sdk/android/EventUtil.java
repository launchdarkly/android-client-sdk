package com.launchdarkly.sdk.android;

import android.os.Build;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DiagnosticDescription;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

abstract class EventUtil {
    static ScheduledExecutorService makeEventsTaskExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                makeThreadFactory("LaunchDarkly-DefaultEventProcessor-%d"));
    }

    /**
     * A thread of its own for posting diagnostic events, kept apart from the one that delivers
     * analytics events.
     * <p>
     * A diagnostic post is an HTTP request like any other and can hold its thread for tens of seconds
     * against a network that accepts connections and never answers. Sharing a thread with analytics
     * delivery would let that stall the flushes, and an event buffer that is not being drained fills
     * up and starts dropping what the application asked to send. This mirrors the separation
     * {@code DefaultEventProcessor} has, where analytics go out on dedicated delivery workers and
     * diagnostics on the shared executor.
     */
    static ExecutorService makeDiagnosticsTaskExecutor() {
        return Executors.newSingleThreadExecutor(
                makeThreadFactory("LaunchDarkly-DiagnosticEventPoster-%d"));
    }

    private static ThreadFactory makeThreadFactory(String nameFormat) {
        return new ThreadFactory() {
            final AtomicLong count = new AtomicLong(0);
            @Override
            public Thread newThread(@NonNull Runnable r) {
                Thread thread = Executors.defaultThreadFactory().newThread(r);
                thread.setName(String.format(Locale.ROOT, nameFormat, count.getAndIncrement()));
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    static DiagnosticStore.SdkDiagnosticParams makeDiagnosticParams(ClientContext clientContext) {
        LDConfig config = clientContext.getConfig();
        String mobileKey = clientContext.getMobileKey();
        ObjectBuilder configProperties = LDValue.buildObject()
                .put("customBaseURI", !StandardEndpoints.DEFAULT_POLLING_BASE_URI.equals(
                        config.serviceEndpoints.getPollingBaseUri()))
                .put("customEventsURI", !StandardEndpoints.DEFAULT_EVENTS_BASE_URI.equals(
                        config.serviceEndpoints.getEventsBaseUri()))
                .put("customStreamURI", !StandardEndpoints.DEFAULT_STREAMING_BASE_URI.equals(
                        config.serviceEndpoints.getStreamingBaseUri()))
                .put("backgroundPollingDisabled", config.isDisableBackgroundPolling())
                .put("evaluationReasonsRequested", config.isEvaluationReasons())
                .put("mobileKeyCount", config.getMobileKeys().size())
                .put("maxCachedUsers", config.getMaxCachedContexts()); // Caution: maxCachedUsers used in production
        mergeComponentProperties(configProperties, config.dataSource);
        mergeComponentProperties(configProperties, config.events);
        mergeComponentProperties(configProperties, config.http);
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, String> kv: LDUtil.makeHttpProperties(clientContext).getDefaultHeaders()) {
            headers.put(kv.getKey(), kv.getValue());
        }
        return new DiagnosticStore.SdkDiagnosticParams(
                mobileKey,
                LDPackageConsts.SDK_NAME,
                BuildConfig.VERSION_NAME,
                LDPackageConsts.SDK_PLATFORM_NAME,
                LDValue.buildObject().put("androidSDKVersion", Build.VERSION.SDK_INT).build(),
                headers,
                Collections.singletonList(configProperties.build())
        );
    }

    private static void mergeComponentProperties(ObjectBuilder builder, ComponentConfigurer<?> componentConfigurer) {
        if (componentConfigurer instanceof DiagnosticDescription) {
            LDValue description = ((DiagnosticDescription)componentConfigurer).describeConfiguration(null);
            for (String key: description.keys()) {
                builder.put(key, description.get(key));
            }
        }
    }
}
