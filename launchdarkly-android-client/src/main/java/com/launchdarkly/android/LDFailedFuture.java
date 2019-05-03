package com.launchdarkly.android;

import android.support.annotation.NonNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class LDFailedFuture<T> implements Future<T> {
    private final Throwable error;

    LDFailedFuture(Throwable error) {
        this.error = error;
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
        return true;
    }

    @Override
    public T get() throws ExecutionException {
        throw new ExecutionException(error);
    }

    @Override
    public T get(long timeout, @NonNull TimeUnit unit) throws ExecutionException {
        throw new ExecutionException(error);
    }
}
