package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ScheduledExecutorService} with a manual clock, for unit tests of timer-driven code
 * whose tasks block (for example on network I/O) and so must run on real background threads.
 * <p>
 * Tasks submitted for immediate execution, and scheduled tasks with no delay, run right away on
 * a background thread. A scheduled task with a delay is held until the test moves the clock past
 * that delay with {@link #advanceTime(long)}, and is then released to a background thread. A test
 * can wait for the code under test to schedule something with
 * {@link #awaitScheduledDelayMillis(long)} and assert on the delay it chose, and can assert that
 * a held task has not run because the clock has not moved, without sleeping.
 */
public class FakeScheduledExecutorService extends AbstractExecutorService implements ScheduledExecutorService {
    private final ExecutorService delegate = Executors.newCachedThreadPool();
    private final Object lock = new Object();
    private final List<HeldTask> held = new ArrayList<>();
    private final BlockingQueue<Long> scheduledDelays = new LinkedBlockingQueue<>();
    private long nowMillis = 0;

    @Override
    public void execute(@NonNull Runnable command) {
        delegate.execute(command);
    }

    @NonNull
    @Override
    public ScheduledFuture<?> schedule(@NonNull Runnable command, long delay, @NonNull TimeUnit unit) {
        long delayMillis = Math.max(0, unit.toMillis(delay));
        HeldTask task;
        synchronized (lock) {
            task = new HeldTask(command, nowMillis + delayMillis);
            if (delayMillis == 0) {
                delegate.execute(task);
            } else {
                held.add(task);
            }
        }
        scheduledDelays.add(delayMillis);
        return task;
    }

    /**
     * Waits for the code under test to call {@link #schedule(Runnable, long, TimeUnit)} and
     * returns the delay it asked for, in milliseconds. Each call consumes one scheduling event,
     * in the order they happened.
     *
     * @param timeoutMillis how long to wait for a scheduling event
     * @return the scheduled delay in milliseconds
     * @throws AssertionError if nothing is scheduled within the timeout
     */
    public long awaitScheduledDelayMillis(long timeoutMillis) throws InterruptedException {
        Long delay = scheduledDelays.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (delay == null) {
            throw new AssertionError("timed out waiting for a task to be scheduled");
        }
        return delay;
    }

    /**
     * @return how long, from the fake clock's current time, until each held task is due, in the
     * order the tasks were scheduled; cancelled and already-released tasks are omitted
     */
    public List<Long> pendingDelaysMillis() {
        List<Long> delays = new ArrayList<>();
        synchronized (lock) {
            for (HeldTask task : held) {
                if (!task.isCancelled()) {
                    delays.add(task.dueMillis - nowMillis);
                }
            }
        }
        return delays;
    }

    /**
     * Moves the clock forward and releases every held task that is due by the new time, in due
     * order, to a background thread.
     */
    public void advanceTime(long millis) {
        List<HeldTask> due = new ArrayList<>();
        synchronized (lock) {
            nowMillis += millis;
            Iterator<HeldTask> it = held.iterator();
            while (it.hasNext()) {
                HeldTask task = it.next();
                if (task.isCancelled()) {
                    it.remove();
                } else if (task.dueMillis <= nowMillis) {
                    it.remove();
                    due.add(task);
                }
            }
        }
        Collections.sort(due, new Comparator<HeldTask>() {
            @Override
            public int compare(HeldTask a, HeldTask b) {
                return Long.compare(a.dueMillis, b.dueMillis);
            }
        });
        for (HeldTask task : due) {
            delegate.execute(task);
        }
    }

    @NonNull
    @Override
    public <V> ScheduledFuture<V> schedule(@NonNull Callable<V> callable, long delay, @NonNull TimeUnit unit) {
        throw new UnsupportedOperationException("FakeScheduledExecutorService does not support Callable scheduling");
    }

    @NonNull
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(@NonNull Runnable command, long initialDelay, long period, @NonNull TimeUnit unit) {
        throw new UnsupportedOperationException("FakeScheduledExecutorService does not support repeating tasks");
    }

    @NonNull
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(@NonNull Runnable command, long initialDelay, long delay, @NonNull TimeUnit unit) {
        throw new UnsupportedOperationException("FakeScheduledExecutorService does not support repeating tasks");
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            held.clear();
        }
        delegate.shutdown();
    }

    @NonNull
    @Override
    public List<Runnable> shutdownNow() {
        synchronized (lock) {
            held.clear();
        }
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    private final class HeldTask extends FutureTask<Void> implements ScheduledFuture<Void> {
        final long dueMillis;

        HeldTask(Runnable command, long dueMillis) {
            super(command, null);
            this.dueMillis = dueMillis;
        }

        @Override
        public long getDelay(@NonNull TimeUnit unit) {
            synchronized (lock) {
                return unit.convert(dueMillis - nowMillis, TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public int compareTo(@NonNull Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
