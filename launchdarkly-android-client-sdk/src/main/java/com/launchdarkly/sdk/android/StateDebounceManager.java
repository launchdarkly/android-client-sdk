package com.launchdarkly.sdk.android;

import java.util.concurrent.ScheduledFuture;

/**
 * Debounces platform state changes (network, lifecycle) into a single
 * reconciliation callback (CONNMODE 3.5). Each state change resets a timer; when
 * the timer fires, the callback runs only if the accumulated state differs from
 * the state at last fire (raw-state A→B→C→A coalescing).
 * <p>
 * When {@code debounceMs == 0} ("immediate mode"), the callback is invoked
 * synchronously on each state change with no timer and no dedup. This allows
 * FDv1 data sources to share the same code path as FDv2 without introducing any
 * delay. Dedup is intentionally skipped in immediate mode because (a) there is
 * no debounce window for state to oscillate within, and (b) listener callbacks
 * can fire on different threads, which would race on the {@code lastApplied*}
 * fields if dedup were active.
 * <p>
 * {@code identify()} does NOT participate in debounce (CONNMODE 3.5.6). Callers
 * handle this by invoking {@link #reset(boolean, boolean)} on each identify,
 * which cancels any pending timer and re-seeds the state mirrors and the
 * A→B→C→A baseline so the new context starts with a clean window.
 */
final class StateDebounceManager {

    static final long DEFAULT_DEBOUNCE_MS = 1000;

    private final Object lock = new Object();
    private final TaskExecutor taskExecutor;
    private final long debounceMs;
    private final Runnable onReconcile;

    private volatile boolean networkAvailable;
    private volatile boolean foreground;

    // Snapshot of (networkAvailable, foreground) at the last successful fire (or
    // initial seed / reset). Used by fireIfChanged() to suppress no-op timer fires
    // when raw state has returned to the prior baseline within a debounce window.
    // Normally read/written from the single AndroidTaskExecutor thread inside
    // fireIfChanged(); reset() also writes them from the caller's thread (under
    // {@code lock}), so they are volatile to ensure visibility. The reconcile
    // callback is itself idempotent — it re-reads {@link PlatformState} and only
    // rebuilds the data source when the resolved mode actually changes — so any
    // spurious fire under unusual interleavings is harmless.
    private volatile boolean lastAppliedNetworkAvailable;
    private volatile boolean lastAppliedForeground;

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
        this.lastAppliedNetworkAvailable = initialNetworkAvailable;
        this.lastAppliedForeground = initialForeground;
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

    void close() {
        closed = true;
        synchronized (lock) {
            if (lastTask != null) {
                lastTask.cancel(false);
                lastTask = null;
            }
        }
    }

    /**
     * Resets the manager so the next debounce window starts clean: cancels any
     * pending timer, re-seeds the state mirrors and the A→B→C→A baseline. The
     * {@code onReconcile} callback is NOT invoked. Used on {@code identify()}
     * (CONNMODE 3.5.6) where the new context bypasses any in-flight debounce
     * accumulated for the previous context.
     * <p>
     * No-op if {@link #close()} has already been called — a closed manager is
     * not resurrected.
     * <p>
     * Holds {@code lock} for the duration of the cancel + re-seed so a
     * concurrent {@link #setNetworkAvailable(boolean)} or {@link #setForeground(boolean)}
     * cannot interleave its {@code resetTimer} call between the cancel and the
     * field updates. A setter that lands between the field writes here and a
     * subsequent timer schedule will simply produce a fresh window with the
     * setter's intended state, which is the correct behavior.
     */
    void reset(boolean networkAvailable, boolean foreground) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (lastTask != null) {
                lastTask.cancel(false);
                lastTask = null;
            }
            this.networkAvailable = networkAvailable;
            this.foreground = foreground;
            this.lastAppliedNetworkAvailable = networkAvailable;
            this.lastAppliedForeground = foreground;
        }
    }

    private void resetTimer() {
        if (closed) {
            return;
        }
        if (debounceMs == 0) {
            // FDv1 immediate mode: fire synchronously, bypass dedup. See class
            // Javadoc for why dedup is intentionally skipped here.
            onReconcile.run();
            return;
        }
        synchronized (lock) {
            if (lastTask != null) {
                lastTask.cancel(false);
            }
            lastTask = taskExecutor.scheduleTask(this::fireIfChanged, debounceMs);
        }
    }

    /**
     * Invoked when a scheduled debounce timer fires. Compares the current raw
     * state (network, foreground) to the state at last fire. If they match — i.e.
     * the platform churned through one or more transitions and ended up where it
     * started within the debounce window — the callback is suppressed.
     * <p>
     * Only reached via the scheduled task on the {@link AndroidTaskExecutor}'s
     * single thread, so {@code lastApplied*} access is naturally serialized.
     */
    private void fireIfChanged() {
        if (closed) {
            return;
        }
        boolean nowNetwork = networkAvailable;
        boolean nowFg = foreground;
        if (nowNetwork == lastAppliedNetworkAvailable && nowFg == lastAppliedForeground) {
            return;
        }
        lastAppliedNetworkAvailable = nowNetwork;
        lastAppliedForeground = nowFg;
        onReconcile.run();
    }
}
