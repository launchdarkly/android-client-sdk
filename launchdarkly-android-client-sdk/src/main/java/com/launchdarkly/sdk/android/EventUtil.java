package com.launchdarkly.sdk.android;

import android.os.Build;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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

    /**
     * A thread of its own for the event commits no caller waits on, kept apart from the one that
     * delivers, so that a write never queues behind a request held open by a slow network.
     */
    static ExecutorService makeEventsCommitExecutor() {
        return Executors.newSingleThreadExecutor(
                makeThreadFactory("LaunchDarkly-event-store-%d"));
    }

    /**
     * Keeps an unexpected failure from killing a repeating task or bubbling out of the executor.
     * Anything that escapes a run suppresses the rest of a {@code scheduleWithFixedDelay} series,
     * so this catches {@code Throwable} and not just {@code Exception}. Otherwise a
     * {@code StackOverflowError} from nested {@code LDValue} data, or an {@code OutOfMemoryError}
     * growing the payload stream, would stop flushing for the life of the process with nothing
     * logged.
     */
    static Runnable guarded(LDLogger logger, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                logUnexpectedError(logger, t);
            }
        };
    }

    static void logUnexpectedError(LDLogger logger, Throwable e) {
        logger.error("Unexpected error in event processor: {}", LogValues.exceptionSummary(e));
        logger.debug("{}", LogValues.exceptionTrace(e));
    }

    /**
     * Keeps a repeating task scheduled while it should be, and canceled while it should not.
     *
     * @param task what to run, already wrapped so that nothing escapes it
     * @return the task now scheduled, or null if there is none
     */
    static ScheduledFuture<?> enableOrDisableTask(
            ScheduledExecutorService scheduler,
            boolean shouldEnable,
            ScheduledFuture<?> currentTask,
            long intervalMillis,
            Runnable task
    ) {
        if (!shouldEnable) {
            if (currentTask != null) {
                currentTask.cancel(false);
            }
            return null;
        }
        if (currentTask != null && !currentTask.isDone()) {
            return currentTask;
        }
        if (currentTask != null) {
            // Backstop for a throwable that escaped the wrapping anyway, such as one thrown while
            // logging the first. The executor marks the repeating future done and never fires it
            // again, and holding that future here would make every later enable a no-op.
            currentTask.cancel(false);
        }
        try {
            // Fixed delay rather than fixed rate: a cached process stops running its tasks without
            // stopping the clock, so at a fixed rate it would come back owing every run it missed and
            // fire them one after another.
            return scheduler.scheduleWithFixedDelay(task, intervalMillis, intervalMillis,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) { // the executor was shut down under us
            return null;
        }
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
