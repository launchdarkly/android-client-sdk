package com.launchdarkly.sdk.android;

import java.util.concurrent.ScheduledFuture;

/**
 * Debounces platform state changes (network, lifecycle) into a single
 * reconciliation callback (CONNMODE 3.5). Each state change resets a timer; when
 * the timer fires, the callback runs with the latest accumulated state.
 * <p>
 * When {@code debounceMs == 0} ("immediate mode"), the callback is invoked
 * synchronously on each state change with no timer. This allows FDv1 data
 * sources to share the same code path as FDv2 without introducing any delay.
 * <p>
 * {@code identify()} does NOT participate in debounce (CONNMODE 3.5.6). Callers
 * handle this by closing and recreating the manager on identify.
 */
final class StateDebounceManager {

    static final long DEFAULT_DEBOUNCE_MS = 1000;

    private final Object lock = new Object();
    private final TaskExecutor taskExecutor;
    private final long debounceMs;
    private final Runnable onReconcile;

    private volatile boolean networkAvailable;
    private volatile boolean foreground;

    private ScheduledFuture<?> lastTask;
    private volatile boolean closed;

    StateDebounceManager(
            boolean initialNetworkAvailable,
            boolean initialForeground,
            TaskExecutor taskExecutor,
            long debounceMs,
            Runnable onReconcile
    ) {
        this.networkAvailable = initialNetworkAvailable;
        this.foreground = initialForeground;
        this.taskExecutor = taskExecutor;
        this.debounceMs = debounceMs;
        this.onReconcile = onReconcile;
    }

    void setNetworkAvailable(boolean available) {
        if (this.networkAvailable == available) {
            return;
        }
        this.networkAvailable = available;
        resetTimer();
    }

    void setForeground(boolean fg) {
        if (this.foreground == fg) {
            return;
        }
        this.foreground = fg;
        resetTimer();
    }

    /**
     * Updates network state without triggering a debounce timer reset. Use when
     * the caller wants to keep state current (so future reconciliation sees the
     * right value) but auto-switching for this axis is disabled.
     */
    void trackNetworkAvailable(boolean available) {
        this.networkAvailable = available;
    }

    /**
     * Updates foreground state without triggering a debounce timer reset. Use when
     * the caller wants to keep state current (so future reconciliation sees the
     * right value) but auto-switching for this axis is disabled.
     */
    void trackForeground(boolean fg) {
        this.foreground = fg;
    }

    boolean isNetworkAvailable() {
        return networkAvailable;
    }

    boolean isForeground() {
        return foreground;
    }

    void close() {
        closed = true;
        synchronized (lock) {
            if (lastTask != null) {
                lastTask.cancel(false);
                lastTask = null;
            }
        }
    }

    private void resetTimer() {
        if (closed) {
            return;
        }
        if (debounceMs == 0) {
            onReconcile.run();
            return;
        }
        synchronized (lock) {
            if (lastTask != null) {
                lastTask.cancel(false);
            }
            lastTask = taskExecutor.scheduleTask(() -> {
                if (!closed) {
                    onReconcile.run();
                }
            }, debounceMs);
        }
    }
}
