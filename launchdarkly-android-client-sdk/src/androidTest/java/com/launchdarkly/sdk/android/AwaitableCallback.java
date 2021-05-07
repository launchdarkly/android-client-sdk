package com.launchdarkly.sdk.android;

import android.os.ConditionVariable;

import com.launchdarkly.sdk.android.LDUtil;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class AwaitableCallback<T> implements LDUtil.ResultCallback<T> {
    private volatile Throwable errResult = null;
    private volatile T result = null;
    private ConditionVariable state = new ConditionVariable();

    @Override
    public void onSuccess(T result) {
        this.result = result;
        state.open();
    }

    @Override
    public void onError(Throwable e) {
        errResult = e;
        state.open();
    }

    synchronized T await() throws ExecutionException {
        state.block();
        if (errResult != null) {
            throw new ExecutionException(errResult);
        }
        return result;
    }

    synchronized T await(long timeoutMillis) throws ExecutionException, TimeoutException {
        boolean opened = state.block(timeoutMillis);
        if (!opened) {
            throw new TimeoutException();
        }
        if (errResult != null) {
            throw new ExecutionException(errResult);
        }
        return result;
    }

    synchronized void reset() {
        state.close();
        errResult = null;
        result = null;
    }
}