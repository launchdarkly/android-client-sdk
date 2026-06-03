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
 */
final class StateDebounceManager {

    static final long DEFAULT_DEBOUNCE_MS = 1000;

    // Two locks, intentionally never held in opposing order, so the reconcile
    // callback can safely re-enter caller code that holds its own monitors
    // without a chance of lock-ordering inversion.
    //
    // taskLock guards the timer state (lastTask) and the field re-seeding done
    // by reset(). It is acquired ONLY by code paths that schedule, cancel, or
    // re-seed the timer; it is never held across onReconcile.run().
    //
    // workLock serializes invocations of onReconcile.run() (FDv2 timer fires
    // and FDv1 immediate-mode setters). close() acquires it as an empty drain
    // barrier so callers can be assured no in-flight reconcile callback
    // outlives close(): see close() for the barrier site.
    private final Object taskLock = new Object();
    private final Object workLock = new Object();

    private final TaskExecutor taskExecutor;
    private final long debounceMs;
    private final Runnable onReconcile;

    private volatile boolean networkAvailable;
    private volatile boolean foreground;

    // Snapshot of (networkAvailable, foreground) at the last successful fire (or
    // initial seed / reset). Used by fireIfChanged() to suppress no-op timer fires
    // when raw state has returned to the prior baseline within a debounce window.
    // Written under workLock from fireIfChanged() and under taskLock from reset();
    // volatile so reset()'s re-seed is visible to the next fireIfChanged() without
    // requiring callers to share a lock. The reconcile callback is itself
    // idempotent — it re-reads {@link PlatformState} and only rebuilds the data
    // source when the resolved mode actually changes — so any spurious fire under
    // unusual interleavings is harmless.
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
        onStateChanged();
    }

    void setForeground(boolean fg) {
        if (this.foreground == fg) {
            return;
        }
        this.foreground = fg;
        onStateChanged();
    }

    /**
     * Routes a state change to the appropriate dispatch path: in immediate
     * mode ({@code debounceMs == 0}) the reconcile callback fires synchronously
     * under {@code workLock}; otherwise the debounce timer is (re)scheduled
     * under {@code taskLock} and the callback runs later via
     * {@link #fireIfChanged()}.
     * <p>
     * Both branches recheck {@code closed} after acquiring their lock so a
     * concurrent {@link #close()} cannot race in between the volatile
     * {@code closed} write and the work being dispatched.
     */
    private void onStateChanged() {
        if (debounceMs == 0) {
            // FDv1 immediate mode: fire synchronously, bypass dedup. See class
            // Javadoc for why dedup is intentionally skipped here.
            synchronized (workLock) {
                if (closed) {
                    return;
                }
                onReconcile.run();
            }
        } else {
            synchronized (taskLock) {
                if (closed) {
                    return;
                }
                resetTimer();
            }
        }
    }

    /**
     * Marks the manager closed, cancels any pending timer, and waits for any
     * in-flight reconcile callback to finish. After {@code close()} returns:
     * <ul>
     *   <li>No future setter call can schedule a new timer or invoke the
     *       reconcile callback (the {@code closed} check inside both locks
     *       short-circuits them).</li>
     *   <li>No reconcile callback is currently executing, because the empty
     *       {@code workLock} block below cannot be entered until any thread
     *       inside {@link #fireIfChanged()} or an FDv1 immediate-mode setter
     *       has released it.</li>
     * </ul>
     * This lets the owner tear down related state immediately after
     * {@code close()} returns without racing the reconcile callback.
     * {@code close()} intentionally does NOT acquire any caller-side monitor;
     * the lock-acquisition graph here keeps the {@code workLock → caller-monitor}
     * edge in {@link #fireIfChanged()} one-way.
     */
    void close() {
        closed = true;
        synchronized (taskLock) {
            if (lastTask != null) {
                lastTask.cancel(false);
                lastTask = null;
            }
        }
        // Drain barrier: blocks until any thread currently inside
        // fireIfChanged() or an FDv1 immediate-mode setter has released
        // workLock. Acquiring and immediately releasing establishes a
        // happens-after relationship with that thread's exit, so once we
        // return the reconcile callback is guaranteed not to be running.
        synchronized (workLock) {
            // intentionally empty
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
     * Holds {@code taskLock} for the duration of the cancel + re-seed so a
     * concurrent FDv2-mode {@link #setNetworkAvailable(boolean)} or
     * {@link #setForeground(boolean)} cannot interleave its {@link #resetTimer()}
     * call between the cancel and the field updates. A setter that lands
     * between the field writes here and a subsequent timer schedule will simply
     * produce a fresh window with the setter's intended state, which is the
     * correct behavior. FDv1 immediate-mode setters acquire {@code workLock}
     * (not {@code taskLock}) and therefore can interleave with reset(); that is
     * harmless because immediate-mode setters do not touch the timer or the
     * A→B→C→A baseline fields.
     */
    void reset(boolean networkAvailable, boolean foreground) {
        synchronized (taskLock) {
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
        if (lastTask != null) {
            lastTask.cancel(false);
        }
        lastTask = taskExecutor.scheduleTask(this::fireIfChanged, debounceMs);
    }

    /**
     * Invoked when a scheduled debounce timer fires. Compares the current raw
     * state (network, foreground) to the state at last fire. If they match — i.e.
     * the platform churned through one or more transitions and ended up where it
     * started within the debounce window — the callback is suppressed.
     * <p>
     * Runs under {@code workLock} so that {@link #close()}'s drain barrier can
     * join with this method. Holding {@code workLock} across {@code onReconcile.run()}
     * is what lets {@code close()} guarantee the callback is no longer executing
     * by the time it returns.
     * <p>
     * The {@code workLock → caller-monitor} acquisition order here is the only
     * direction either lock crosses {@code workLock}; {@code close()} preserves
     * the ordering by not taking any caller-side monitor itself.
     */
    private void fireIfChanged() {
        synchronized (workLock) {
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
}
