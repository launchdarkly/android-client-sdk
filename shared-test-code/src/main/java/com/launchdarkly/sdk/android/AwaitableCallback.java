package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.subsystems.Callback;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AwaitableCallback<T> implements Callback<T> {
    private volatile Throwable errResult = null;
    private volatile T result = null;
    private volatile CountDownLatch signal = new CountDownLatch(1);

    @Override
    public void onSuccess(T result) {
        this.result = result;
        signal.countDown();
    }

    @Override
    public void onError(Throwable e) {
        errResult = e;
        signal.countDown();
    }

    synchronized T await() throws ExecutionException {
        try {
            signal.await();
        } catch (InterruptedException e) {
            throw new ExecutionException(e);
        }
        if (errResult != null) {
            throw new ExecutionException(errResult);
        }
        return result;
    }

    synchronized T await(long timeoutMillis) throws ExecutionException, TimeoutException {
        try {
            boolean completed = signal.await(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new TimeoutException();
            }
        } catch (InterruptedException e) {
            throw new ExecutionException(e);
        }
        if (errResult != null) {
            throw new ExecutionException(errResult);
        }
        return result;
    }

    synchronized void reset() {
        signal = new CountDownLatch(1);
        errResult = null;
        result = null;
    }
}