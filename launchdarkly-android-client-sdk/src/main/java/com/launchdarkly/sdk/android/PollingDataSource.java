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
    private final LDContext context;
    private final DataSourceUpdateSink dataSourceUpdateSink;
    final long initialDelayMillis; // visible for testing
    final long pollIntervalMillis; // visible for testing
    long numberOfPollsRemaining; // visible for testing
    private final FeatureFetcher fetcher;
    private final PlatformState platformState;
    private final TaskExecutor taskExecutor;
    private final LDLogger logger;
    final AtomicReference<ScheduledFuture<?>> currentPollTask = new AtomicReference<>(); // visible for testing

    /**
     * @param context              that this data source will fetch data for
     * @param dataSourceUpdateSink to send data to
     * @param initialDelayMillis   delays when the data source begins polling. If this is greater than 0, the polling data
     *                             source will report success immediately as it is now running even if data has not been
     *                             fetched.
     * @param pollIntervalMillis   interval in millis between each polling request
     * @param maxNumberOfPolls     the maximum number of polling attempts, use Long.MAX for effectively unlimited.
     * @param fetcher              that will be used for each fetch
     * @param platformState        used for making decisions based on platform state
     * @param taskExecutor         that will be used to schedule the polling tasks
     * @param logger               for logging
     */
    PollingDataSource(
            LDContext context,
            DataSourceUpdateSink dataSourceUpdateSink,
            long initialDelayMillis,
            long pollIntervalMillis,
            long maxNumberOfPolls,
            FeatureFetcher fetcher,
            PlatformState platformState,
            TaskExecutor taskExecutor,
            LDLogger logger
    ) {
        this.context = context;
        this.dataSourceUpdateSink = dataSourceUpdateSink;
        this.initialDelayMillis = initialDelayMillis;
        this.pollIntervalMillis = pollIntervalMillis;
        this.numberOfPollsRemaining = maxNumberOfPolls;
        this.fetcher = fetcher;
        this.platformState = platformState;
        this.taskExecutor = taskExecutor;
        this.logger = logger;
    }

    @Override
    public void start(final Callback<Boolean> resultCallback) {
        if (numberOfPollsRemaining <= 0) {
            // If there are no polls to be made, we will immediately report the successful start of the data source.  This
            // may seem strange, but one can think of this data source as behaving like a no-op in this configuration.
            resultCallback.onSuccess(true);
            return;
        }

        Runnable pollRunnable = () -> poll(resultCallback);
        logger.debug("Scheduling polling task with interval of {}ms, starting after {}ms, with number of polls {}",
                pollIntervalMillis, initialDelayMillis, numberOfPollsRemaining);
        ScheduledFuture<?> task = taskExecutor.startRepeatingTask(pollRunnable,
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

    private void poll(Callback<Boolean> resultCallback) {
        // poll if there are polls remaining
        if (numberOfPollsRemaining > 0) {
            numberOfPollsRemaining--;
            ConnectivityManager.fetchAndSetData(fetcher, context, dataSourceUpdateSink,
                    resultCallback, logger);
        } else {
            // terminate if we have no polls remaining
            ScheduledFuture<?> task = currentPollTask.getAndSet(null);
            if (task != null) {
                task.cancel(true);
            }
        }
    }
}
