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
 * tests assert how many times a task was cancelled/rescheduled. {@link #pendingDelaysMillis()}
 * lets tests assert the delay each pending task was scheduled with.
 * <p>
 * Tasks may be scheduled from any thread.
 */
public final class ManualTaskExecutor implements TaskExecutor {
    private final List<ManualScheduledFuture> pending = new ArrayList<>();
    private int cancelledCount = 0;

    /**
     * @return the number of scheduled tasks that have been cancelled
     */
    public synchronized int cancelledCount() {
        return cancelledCount;
    }

    /**
     * @return the delay each pending, non-cancelled task was scheduled with, in milliseconds, in
     * the order the tasks were scheduled
     */
    public synchronized List<Long> pendingDelaysMillis() {
        List<Long> delays = new ArrayList<>();
        for (ManualScheduledFuture task : pending) {
            if (!task.cancelled) {
                delays.add(task.delayMillis);
            }
        }
        return delays;
    }

    /**
     * Runs every pending, non-cancelled task that has been scheduled via
     * {@link #scheduleTask(Runnable, long)} and clears the pending queue. A task scheduled while
     * this runs is left pending for the next call.
     */
    public void runPendingTasks() {
        List<ManualScheduledFuture> toRun;
        synchronized (this) {
            toRun = new ArrayList<>(pending);
            pending.clear();
        }
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
    public synchronized ScheduledFuture<?> scheduleTask(Runnable action, long delayMillis) {
        ManualScheduledFuture future = new ManualScheduledFuture(action, delayMillis);
        pending.add(future);
        return future;
    }

    @Override
    public synchronized ScheduledFuture<?> startRepeatingTask(Runnable action, long initialDelayMillis, long intervalMillis) {
        ManualScheduledFuture future = new ManualScheduledFuture(action, initialDelayMillis);
        pending.add(future);
        return future;
    }

    @Override
    public synchronized void close() {
        pending.clear();
    }

    private final class ManualScheduledFuture implements ScheduledFuture<Object> {
        private final Runnable action;
        private final long delayMillis;
        private volatile boolean cancelled = false;

        ManualScheduledFuture(Runnable action, long delayMillis) {
            this.action = action;
            this.delayMillis = delayMillis;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (ManualTaskExecutor.this) {
                if (!cancelled) {
                    cancelled = true;
                    cancelledCount++;
                }
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
            return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }
    }
}
