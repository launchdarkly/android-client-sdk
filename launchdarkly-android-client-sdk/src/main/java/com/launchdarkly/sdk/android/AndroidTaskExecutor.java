package com.launchdarkly.sdk.android;

import android.os.Handler;
import android.os.Looper;

import com.launchdarkly.logging.LDLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standard implementation of {@link TaskExecutor} for use in the Android environment. Besides
 * enforcing correct thread usage, this class also ensures that any unchecked exceptions thrown by
 * asynchronous tasks are caught and logged.
 */
final class AndroidTaskExecutor implements TaskExecutor {
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private final Handler handler;
    private final LDLogger logger;

    AndroidTaskExecutor(LDLogger logger) {
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
    public void scheduleTask(Runnable action) {
        executor.submit(wrapActionWithErrorHandling(action));
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
