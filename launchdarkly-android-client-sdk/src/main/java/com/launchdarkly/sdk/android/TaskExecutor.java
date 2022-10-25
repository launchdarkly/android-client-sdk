package com.launchdarkly.sdk.android;

import java.io.Closeable;
import java.util.concurrent.ScheduledFuture;

/**
 * Internal abstraction for standardizing how asynchronous tasks are executed.
 */
interface TaskExecutor extends Closeable {
    /**
     * Causes an action to be performed on the main thread. We use this when we are calling
     * application-provided listeners.
     * <p>
     * If we are already on the main thread, the action is called synchronously. Otherwise, it is
     * scheduled to be run asynchronously on the main thread.
     *
     * @param action the action to execute
     */
    void executeOnMainThread(Runnable action);

    /**
     * Schedules an action to be done asynchronously by a worker. It will not be done on the main
     * thread. There are no guarantees as to ordering with other tasks.
     *
     * @param action the action to execute
     * @param delayMillis minimum milliseconds to wait before executing
     */
    ScheduledFuture<?> scheduleTask(Runnable action, long delayMillis);

    /**
     *
     * @param identifier uniquely represents this task
     * @param task the action to execute at each interval
     * @param initialDelayMillis milliseconds to wait before the first execution
     * @param intervalMillis milliseconds between executions
     */
    void startRepeatingTask(Object identifier, Runnable task, long initialDelayMillis, long intervalMillis);

    /**
     *
     * @param identifier uniquely represents this task
     */
    void stopRepeatingTask(Object identifier);
}
