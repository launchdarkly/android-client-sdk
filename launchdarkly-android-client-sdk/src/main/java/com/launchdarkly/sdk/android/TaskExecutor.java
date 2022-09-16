package com.launchdarkly.sdk.android;

/**
 * Internal abstraction for standardizing how asynchronous tasks are executed.
 */
interface TaskExecutor {
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
     */
    void scheduleTask(Runnable action);
}
