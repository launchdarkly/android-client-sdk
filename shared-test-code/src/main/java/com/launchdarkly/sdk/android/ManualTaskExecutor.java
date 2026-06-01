package com.launchdarkly.sdk.android;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A {@link TaskExecutor} whose scheduled tasks only run when the test explicitly calls
 * {@link #runPendingTasks()}, making time-dependent behavior deterministic in unit tests.
 * <p>
 * This avoids {@code Thread.sleep}-based timing, which is flaky on loaded CI runners. Cancelled
 * tasks (e.g. when a debounce timer is reset) are never run, and {@link #cancelledCount()} lets
 * tests assert how many times a task was cancelled/rescheduled.
 */
public final class ManualTaskExecutor implements TaskExecutor {
    private final List<ManualScheduledFuture> pending = new ArrayList<>();
    private int cancelledCount = 0;

    /**
     * @return the number of scheduled tasks that have been cancelled
     */
    public int cancelledCount() {
        return cancelledCount;
    }

    /**
     * Runs every pending, non-cancelled task that has been scheduled via
     * {@link #scheduleTask(Runnable, long)} and clears the pending queue.
     */
    public void runPendingTasks() {
        List<ManualScheduledFuture> toRun = new ArrayList<>(pending);
        pending.clear();
        for (ManualScheduledFuture task : toRun) {
            if (!task.cancelled) {
                task.action.run();
            }
        }
    }

    @Override
    public void executeOnMainThread(Runnable action) {
        action.run();
    }

    @Override
    public ScheduledFuture<?> scheduleTask(Runnable action, long delayMillis) {
        ManualScheduledFuture future = new ManualScheduledFuture(action);
        pending.add(future);
        return future;
    }

    @Override
    public ScheduledFuture<?> startRepeatingTask(Runnable action, long initialDelayMillis, long intervalMillis) {
        return new ManualScheduledFuture(action);
    }

    @Override
    public void close() {
        pending.clear();
    }

    private final class ManualScheduledFuture implements ScheduledFuture<Object> {
        private final Runnable action;
        private boolean cancelled = false;

        ManualScheduledFuture(Runnable action) {
            this.action = action;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!cancelled) {
                cancelled = true;
                cancelledCount++;
            }
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }
    }
}
