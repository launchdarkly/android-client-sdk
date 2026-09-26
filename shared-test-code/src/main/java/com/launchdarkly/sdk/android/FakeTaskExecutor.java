package com.launchdarkly.sdk.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A {@link TaskExecutor} with a manual clock, for unit tests of timer-driven code.
 * <p>
 * A scheduled task does not run until the test moves the clock past its delay with
 * {@link #advanceTime(long)}, and then it runs synchronously on the calling thread. A test can
 * therefore assert exactly which delays were scheduled, and that nothing ran before the clock
 * moved, without sleeping.
 */
public class FakeTaskExecutor implements TaskExecutor {
    private final Object lock = new Object();
    private final List<ScheduledTask> tasks = new ArrayList<>();
    private long nowMillis = 0;

    @Override
    public void executeOnMainThread(Runnable action) {
        action.run();
    }

    @Override
    public ScheduledFuture<?> scheduleTask(Runnable action, long delayMillis) {
        synchronized (lock) {
            ScheduledTask task = new ScheduledTask(action, nowMillis + Math.max(0, delayMillis));
            tasks.add(task);
            return task;
        }
    }

    @Override
    public ScheduledFuture<?> startRepeatingTask(Runnable action, long initialDelayMillis, long intervalMillis) {
        throw new UnsupportedOperationException("FakeTaskExecutor does not support repeating tasks");
    }

    @Override
    public void close() {
        synchronized (lock) {
            tasks.clear();
        }
    }

    /**
     * @return how long, from the fake clock's current time, until each pending task is due, in
     * the order the tasks were scheduled; cancelled tasks are omitted
     */
    public List<Long> pendingDelaysMillis() {
        List<Long> delays = new ArrayList<>();
        synchronized (lock) {
            for (ScheduledTask task : tasks) {
                if (!task.isCancelled()) {
                    delays.add(task.dueMillis - nowMillis);
                }
            }
        }
        return delays;
    }

    /**
     * Runs every task whose time has already come, without moving the clock.
     */
    public void runDueTasks() {
        advanceTime(0);
    }

    /**
     * Moves the clock forward and runs every task that is due by the new time, in due order, on
     * the calling thread. A task that schedules another task during its run is also run here if
     * that task is due.
     */
    public void advanceTime(long millis) {
        synchronized (lock) {
            nowMillis += millis;
        }
        while (true) {
            List<ScheduledTask> due = new ArrayList<>();
            synchronized (lock) {
                Iterator<ScheduledTask> it = tasks.iterator();
                while (it.hasNext()) {
                    ScheduledTask task = it.next();
                    if (task.isCancelled()) {
                        it.remove();
                    } else if (task.dueMillis <= nowMillis) {
                        it.remove();
                        due.add(task);
                    }
                }
            }
            if (due.isEmpty()) {
                return;
            }
            Collections.sort(due, new Comparator<ScheduledTask>() {
                @Override
                public int compare(ScheduledTask a, ScheduledTask b) {
                    return Long.compare(a.dueMillis, b.dueMillis);
                }
            });
            for (ScheduledTask task : due) {
                task.run();
            }
        }
    }

    private final class ScheduledTask extends FutureTask<Void> implements ScheduledFuture<Void> {
        final long dueMillis;

        ScheduledTask(Runnable action, long dueMillis) {
            super(action, null);
            this.dueMillis = dueMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            synchronized (lock) {
                return unit.convert(dueMillis - nowMillis, TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
