package com.launchdarkly.sdk.android;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * An implementation of {@link TaskExecutor} that can be used in unit tests outside of the Android
 * environment. This allows us to unit-test components separately from the implementation details of
 * how threads are managed in Android, verifying only that those components are calling the correct
 * {@link TaskExecutor} methods.
 */
public class SimpleTestTaskExecutor implements TaskExecutor {
    private static final ThreadLocal<Thread> fakeMainThread = new ThreadLocal<>();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<Object, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    @Override
    public void executeOnMainThread(Runnable action) {
        new Thread(() -> {
            fakeMainThread.set(Thread.currentThread());
            action.run();
        }).start();
    }

    @Override
    public ScheduledFuture<?> scheduleTask(Runnable action, long delayMillis) {
        return executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void startRepeatingTask(Object identifier, Runnable task, long initialDelayMillis, long intervalMillis) {
        ScheduledFuture<?> future =
                executor.scheduleAtFixedRate(task, initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS);
        tasks.put(identifier, future);
    }

    @Override
    public void stopRepeatingTask(Object identifier) {
        ScheduledFuture<?> future = tasks.get(identifier);
        if (future != null) {
            tasks.remove(identifier);
            future.cancel(false);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public boolean isThisTheFakeMainThread() {
        return fakeMainThread.get() == Thread.currentThread();
    }
}
