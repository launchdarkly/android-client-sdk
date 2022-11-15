package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DataSource implementation for polling mode.
 * <p>
 * The SDK uses this implementation if 1. the application has explicitly enabled polling instead of
 * streaming with Components.pollingDataSource(), or 2. streaming is enabled, but the application is
 * in the background so we do polling instead. The logic for this is in
 * ComponentsImpl.PollingDataSourceBuilderImpl and ComponentsImpl.StreamingDataSourceBuilderImpl.
 */
final class PollingDataSource implements DataSource {
    private final LDContext currentContext;
    private final DataSourceUpdateSink dataSourceUpdateSink;
    private final int initialDelayMillis;
    private final int pollIntervalMillis;
    private final FeatureFetcher fetcher;
    private final PlatformState platformState;
    private final TaskExecutor taskExecutor;
    private final LDLogger logger;
    private final AtomicReference<ScheduledFuture<?>> currentPollTask =
            new AtomicReference<>();

    PollingDataSource(
            LDContext currentContext,
            DataSourceUpdateSink dataSourceUpdateSink,
            int initialDelayMillis,
            int pollIntervalMillis,
            FeatureFetcher fetcher,
            PlatformState platformState,
            TaskExecutor taskExecutor,
            LDLogger logger
    ) {
        this.currentContext = currentContext;
        this.dataSourceUpdateSink = dataSourceUpdateSink;
        this.initialDelayMillis = initialDelayMillis;
        this.pollIntervalMillis = pollIntervalMillis;
        this.fetcher = fetcher;
        this.platformState = platformState;
        this.taskExecutor = taskExecutor;
        this.logger = logger;
    }

    @Override
    public void start(final Callback<Boolean> resultCallback) {
        Runnable trigger = new Runnable() {
            @Override
            public void run() {
                triggerPoll(resultCallback);
            }
        };
        logger.debug("Scheduling polling task with interval of {}ms, starting after {}ms",
                pollIntervalMillis, initialDelayMillis);
        ScheduledFuture<?> task = taskExecutor.startRepeatingTask(trigger,
                initialDelayMillis, pollIntervalMillis);
        currentPollTask.set(task);
    }

    @Override
    public void stop(Callback<Void> completionCallback) {
        ScheduledFuture<?> task = currentPollTask.getAndSet(null);
        if (task != null) {
            task.cancel(true);
        }
        completionCallback.onSuccess(null);
    }

    private void triggerPoll(Callback<Boolean> resultCallback) {
        ConnectivityManager.fetchAndSetData(fetcher, currentContext, dataSourceUpdateSink,
                resultCallback, logger);
    }
}
