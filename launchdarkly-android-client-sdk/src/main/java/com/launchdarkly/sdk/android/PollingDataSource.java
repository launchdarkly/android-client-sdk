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
 * <p>
 * Polls happen at the poll interval, except that a failure that is not expected to resolve on
 * its own, such as HTTP 401, is followed by a much longer wait. No failure stops the data source
 * from polling again.
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

    // Guarded by the lock on this instance.
    private final RetryState retryState;
    private boolean running = false;

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
        this(context, dataSourceUpdateSink, initialDelayMillis, pollIntervalMillis, maxNumberOfPolls,
                fetcher, platformState, taskExecutor, RetryState.forPolling(pollIntervalMillis), logger);
    }

    /**
     * This constructor allows tests to supply a {@link RetryState} with short delays. See the
     * other constructor for the remaining parameters.
     *
     * @param retryState the retry state that decides the wait after a failed poll
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
            RetryState retryState,
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
        this.retryState = retryState;
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

        synchronized (this) {
            running = true;
        }
        logger.debug("Scheduling polling task with interval of {}ms, starting after {}ms, with number of polls {}",
                pollIntervalMillis, initialDelayMillis, numberOfPollsRemaining);
        schedulePoll(initialDelayMillis, resultCallback);
    }

    @Override
    public void stop(Callback<Void> completionCallback) {
        synchronized (this) {
            running = false;
        }
        ScheduledFuture<?> task = currentPollTask.getAndSet(null);
        if (task != null) {
            task.cancel(true);
        }
        completionCallback.onSuccess(null);
    }

    /**
     * Schedules the next poll, unless the data source has been stopped or has used up its polls.
     */
    private synchronized void schedulePoll(long delayMillis, Callback<Boolean> resultCallback) {
        if (!running || numberOfPollsRemaining <= 0) {
            return;
        }
        currentPollTask.set(taskExecutor.scheduleTask(() -> poll(resultCallback), delayMillis));
    }

    private void poll(final Callback<Boolean> resultCallback) {
        synchronized (this) {
            if (!running || numberOfPollsRemaining <= 0) {
                return;
            }
            numberOfPollsRemaining--;
        }

        Callback<Boolean> pollCallback = new Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                long delay;
                synchronized (PollingDataSource.this) {
                    retryState.recordSuccess(System.currentTimeMillis());
                    delay = retryState.nextDelayMillis();
                }
                resultCallback.onSuccess(result);
                schedulePoll(delay, resultCallback);
            }

            @Override
            public void onError(Throwable error) {
                boolean unexpected = LDUtil.isUnexpectedFailure(error);
                long delay;
                synchronized (PollingDataSource.this) {
                    retryState.recordFailure(unexpected, System.currentTimeMillis());
                    delay = retryState.nextDelayMillis();
                }
                if (unexpected) {
                    logger.error("Received HTTP error {} from polling request. This is not expected to resolve on its own; verify correct Mobile Key and Polling URI. Will retry in {} ms.",
                            ((LDInvalidResponseCodeFailure) error).getResponseCode(), delay);
                } else {
                    logger.warn("Polling request failed. Will retry in {} ms.", delay);
                }
                resultCallback.onError(error);
                schedulePoll(delay, resultCallback);
            }
        };

        try {
            ConnectivityManager.fetchAndSetData(fetcher, context, dataSourceUpdateSink, pollCallback, logger);
        } catch (RuntimeException e) {
            // If the fetcher throws instead of using its callback, the caller is still owed a
            // result and the next poll.
            LDUtil.logExceptionAtErrorLevel(logger, e, "Unexpected exception while polling for flags");
            pollCallback.onError(new LDFailure("Exception while fetching flags", e, LDFailure.FailureType.UNKNOWN_ERROR));
        }
    }
}
