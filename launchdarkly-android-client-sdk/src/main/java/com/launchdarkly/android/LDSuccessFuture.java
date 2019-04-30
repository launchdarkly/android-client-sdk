package com.launchdarkly.android;

import android.support.annotation.NonNull;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class LDSuccessFuture<T> implements Future<T> {
    private final T result;

    LDSuccessFuture(T result) {
        this.result = result;
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
    public T get() {
        return result;
    }

    @Override
    public T get(long timeout, @NonNull TimeUnit unit) {
        return result;
    }
}
