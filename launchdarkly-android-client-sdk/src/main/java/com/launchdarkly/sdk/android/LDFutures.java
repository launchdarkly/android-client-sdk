package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final ExecutorService BRIDGE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "LaunchDarkly-FutureBridge");
        t.setDaemon(true);
        return t;
    });

    /**
     * Converts any Future to an LDAwaitFuture that completes when the given future completes.
     * If the future is already an LDAwaitFuture, returns it as-is.
     * <p>
     * If the future is already done, it is resolved synchronously without spawning a thread.
     * Otherwise, a pooled daemon thread blocks on the future until it completes.
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
        if (future.isDone()) {
            try {
                result.set(future.get());
            } catch (Throwable t) {
                result.setException(t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t);
            }
            return result;
        }
        BRIDGE_EXECUTOR.execute(() -> {
            try {
                result.set(future.get());
            } catch (Throwable t) {
                result.setException(t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t);
            }
        });
        return result;
    }

    /**
     * Returns a future that completes when the first of the given futures completes.
     * Equivalent to CompletableFuture.anyOf. Works with any {@link Future} (API-level safe).
     *
     * @param futures the futures to race (null or empty returns a future that never completes)
     * @param <T>     common result type; use {@code Object} when mixing futures of different types
     * @return an {@link LDAwaitFuture} that completes with the first result (or the first exception)
     */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public static <T> LDAwaitFuture<T> anyOf(Future<? extends T>... futures) {
        if (futures == null || futures.length == 0) {
            return new LDAwaitFuture<>();
        }
        LDAwaitFuture<T> result = new LDAwaitFuture<>();
        AtomicBoolean won = new AtomicBoolean(false);
        LDAwaitFuture<?>[] awaitables = new LDAwaitFuture<?>[futures.length];
        Runnable[] listeners = new Runnable[futures.length];
        for (int i = 0; i < futures.length; i++) {
            Future<?> f = futures[i];
            LDAwaitFuture<?> awaitable = f instanceof LDAwaitFuture ? (LDAwaitFuture<?>) f : fromFuture(f);
            awaitables[i] = awaitable;
            Runnable listener = () -> {
                if (won.compareAndSet(false, true)) {
                    try {
                        result.set((T) awaitable.get());
                    } catch (Throwable t) {
                        result.setException(t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t);
                    }
                    // Remove this listener from all futures so long-lived ones (e.g. conditions)
                    // don't accumulate listeners when another future wins each iteration.
                    for (int j = 0; j < awaitables.length; j++) {
                        awaitables[j].removeListener(listeners[j]);
                    }
                }
            };
            listeners[i] = listener;
        }
        for (int i = 0; i < futures.length; i++) {
            awaitables[i].addListener(listeners[i]);
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
    private List<Runnable> listeners = new ArrayList<>();

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
                listeners.add(listener);
                return;
            }
        }
        listener.run();
    }

    /**
     * Removes a listener that was previously added. Used by anyOf to avoid accumulating
     * listeners on long-lived futures when the race is decided by another future.
     */
    void removeListener(@NonNull Runnable listener) {
        synchronized (lock) {
            if (listeners != null) {
                listeners.remove(listener);
            }
        }
    }

    /** For tests: returns current listener count so accumulation can be asserted. */
    int getListenerCount() {
        synchronized (lock) {
            return listeners == null ? 0 : listeners.size();
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
