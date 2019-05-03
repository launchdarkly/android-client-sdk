package com.launchdarkly.android;

import android.support.annotation.NonNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import timber.log.Timber;

class LDAwaitFuture<T> implements Future<T> {
    private volatile T result = null;
    private volatile Throwable error = null;
    private volatile boolean completed = false;
    private final Object notifier = new Object();

    LDAwaitFuture() {}

    synchronized void set(T result) {
        if (!completed) {
            this.result = result;
            synchronized (notifier) {
                completed = true;
                notifier.notifyAll();
            }
        } else {
            Timber.w("LDAwaitFuture set twice");
        }
    }

    synchronized void setException(Throwable error) {
        if (!completed) {
            this.error = error;
            synchronized (notifier) {
                completed = true;
                notifier.notifyAll();
            }
        } else {
            Timber.w("LDAwaitFuture set twice");
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean isDone() {
        return completed;
    }

    @Override
    public T get() throws ExecutionException, InterruptedException {
        synchronized (notifier) {
            if (!completed) {
                notifier.wait();
            }
        }
        if (error != null) {
            throw new ExecutionException(error);
        }
        return result;
    }

    @Override
    public T get(long timeout, @NonNull TimeUnit unit) throws ExecutionException, TimeoutException, InterruptedException {
        synchronized (notifier) {
            if (!completed) {
                long millis = TimeUnit.MILLISECONDS.convert(timeout, unit);
                notifier.wait(millis);
            }
        }
        if (!completed) {
            throw new TimeoutException("LDAwaitFuture timed out awaiting completion");
        }
        if (error != null) {
            throw new ExecutionException(error);
        }
        return result;
    }
}
