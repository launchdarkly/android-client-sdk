package com.launchdarkly.sdk.android;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An implementation of {@link TaskExecutor} that can be used in unit tests outside of the Android
 * environment. This allows us to unit-test components separately from the implementation details of
 * how threads are managed in Android, verifying only that those components are calling the correct
 * {@link TaskExecutor} methods.
 */
public class SimpleTestTaskExecutor implements TaskExecutor {
    private static final ThreadLocal<Thread> fakeMainThread = new ThreadLocal<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @Override
    public void executeOnMainThread(Runnable action) {
        new Thread(() -> {
            fakeMainThread.set(Thread.currentThread());
            action.run();
        }).start();
    }

    @Override
    public void scheduleTask(Runnable action) {
        executor.submit(action);
    }

    public boolean isThisTheFakeMainThread() {
        return fakeMainThread.get() == Thread.currentThread();
    }
}
