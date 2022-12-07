package com.launchdarkly.sdk.android;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.launchdarkly.logging.LDLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Standard implementation of {@link TaskExecutor} for use in the Android environment. Besides
 * enforcing correct thread usage, this class also ensures that any unchecked exceptions thrown by
 * asynchronous tasks are caught and logged.
 */
final class AndroidTaskExecutor implements TaskExecutor {
    private final Application application;
    private final Handler handler;
    private final LDLogger logger;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    AndroidTaskExecutor(Application application, LDLogger logger) {
        this.application = application;
        this.handler = new Handler(Looper.getMainLooper());
        this.logger = logger;
    }

    @Override
    public void executeOnMainThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callActionWithErrorHandling(action);
        } else {
            handler.post(wrapActionWithErrorHandling(action));
        }
    }

    @Override
    public ScheduledFuture<?> scheduleTask(Runnable action, long delayMillis) {
        return executor.schedule(wrapActionWithErrorHandling(action), delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public ScheduledFuture<?> startRepeatingTask(Runnable action, long initialDelayMillis, long intervalMillis) {
        return executor.scheduleAtFixedRate(wrapActionWithErrorHandling(action),
                initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private Runnable wrapActionWithErrorHandling(Runnable action) {
        return new Runnable() {
            @Override
            public void run() {
                callActionWithErrorHandling(action);
            }
        };
    }

    private void callActionWithErrorHandling(Runnable action) {
        try {
            if (action != null) {
                action.run();
            }
        } catch (RuntimeException e) {
            LDUtil.logExceptionAtErrorLevel(logger, e, "Unexpected exception from asynchronous task");
        }
    }
}
