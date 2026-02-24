package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helpers for asynchronous results, including settable futures and anyOf for racing completions.
 * <p>
 * Use this when you need CompletableFuture-like behavior (e.g. anyOf) on all Android API levels.
 * {@link LDAwaitFuture} is a settable {@link Future}; {@link #anyOf(Future[])} returns when the
 * first of several futures completes.
 */
public final class LDFutures {
    private LDFutures() {}

    /**
     * Converts any Future to an LDAwaitFuture that completes when the given future completes.
     * If the future is already an LDAwaitFuture, returns it as-is.
     *
     * @param future the future to wrap
     * @param <T>    result type
     * @return an LDAwaitFuture that completes with the same result or exception
     */
    public static <T> LDAwaitFuture<T> fromFuture(Future<T> future) {
        if (future instanceof LDAwaitFuture) {
            @SuppressWarnings("unchecked")
            LDAwaitFuture<T> already = (LDAwaitFuture<T>) future;
            return already;
        }
        LDAwaitFuture<T> result = new LDAwaitFuture<>();
        new Thread(() -> {
            try {
                result.set(future.get());
            } catch (Throwable t) {
                result.setException(t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t);
            }
        }, "LaunchDarkly-FutureBridge").start();
        return result;
    }

    /**
     * Returns a future that completes when the first of the given futures completes.
     * Equivalent to CompletableFuture.anyOf. Works with any {@link Future} (API-level safe).
     *
     * @param futures the futures to race (null or empty returns a future that never completes)
     * @return a Future that completes with the first result (or the first exception)
     */
    @SafeVarargs
    public static Future<Object> anyOf(Future<?>... futures) {
        if (futures == null || futures.length == 0) {
            return new LDAwaitFuture<>();
        }
        LDAwaitFuture<Object> result = new LDAwaitFuture<>();
        AtomicBoolean won = new AtomicBoolean(false);
        for (Future<?> f : futures) {
            LDAwaitFuture<?> awaitable = f instanceof LDAwaitFuture ? (LDAwaitFuture<?>) f : fromFuture(f);
            awaitable.addListener(() -> {
                if (won.compareAndSet(false, true)) {
                    try {
                        result.set(awaitable.get());
                    } catch (Throwable t) {
                        result.setException(t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t);
                    }
                }
            });
        }
        return result;
    }
}

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

class LDAwaitFuture<T> implements Future<T> {
    private volatile T result = null;
    private volatile Throwable error = null;
    private volatile boolean completed = false;
    private final Object lock = new Object();
    private List<Runnable> listeners = null;

    LDAwaitFuture() {}

    void set(T value) {
        List<Runnable> toNotify = completeWith(value, null);
        runListeners(toNotify);
    }

    void setException(@NonNull Throwable error) {
        List<Runnable> toNotify = completeWith(null, error);
        runListeners(toNotify);
    }

    void addListener(@NonNull Runnable listener) {
        synchronized (lock) {
            if (!completed) {
                if (listeners == null) {
                    listeners = new ArrayList<>();
                }
                listeners.add(listener);
                return;
            }
        }
        listener.run();
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
        synchronized (lock) {
            while (!completed) {
                lock.wait();
            }
        }
        if (error != null) {
            throw new ExecutionException(error);
        }
        return result;
    }

    @Override
    public T get(long timeout, @NonNull TimeUnit unit) throws ExecutionException,
            TimeoutException, InterruptedException {
        long remaining = unit.toNanos(timeout);
        long doneAt = remaining + System.nanoTime();
        synchronized (lock) {
            while (!completed && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                remaining = doneAt - System.nanoTime();
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

    private List<Runnable> completeWith(T value, Throwable err) {
        synchronized (lock) {
            if (completed) {
                return null;
            }
            this.result = value;
            this.error = err;
            this.completed = true;
            List<Runnable> toNotify = listeners;
            listeners = null;
            lock.notifyAll();
            return toNotify;
        }
    }

    private static void runListeners(List<Runnable> listeners) {
        if (listeners != null) {
            for (Runnable r : listeners) {
                r.run();
            }
        }
    }
}
