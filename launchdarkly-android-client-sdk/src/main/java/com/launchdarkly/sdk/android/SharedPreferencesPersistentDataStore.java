package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.VisibleForTesting;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SharedPreferences-backed persistent data store with in-memory state coalescing. The
 * state coalescing was added to improve memory footprint in large bursts of changes
 * to a single key.  SharedPreferences does not internally coalesce, so this improves
 * its behavior.
 * <p>
 * Each namespace has a resolved {@link State}. Every incoming call is folded into the
 * pending state for its namespace, producing a single resolved state regardless of how
 * many calls were made. A background flush task drains the pending state into a committing
 * state and writes each namespace's state to disk in a single
 * {@link SharedPreferences.Editor#commit()} call.
 * <p>
 * Reads resolve in a read through manner: pending → committing → {@link SharedPreferences}.
 * <p>
 * All public writing methods return promptly; the actual disk I/O happens on a shared
 * background executor via {@code commit()}. Multiple rapid operations on the same
 * namespace collapse into one flush entry — a burst of {@code setValue}s produces a
 * single commit, and a {@code clear} discards any pending writes for that namespace.
 * <p>
 * While there are pending writes, the background flush drain is throttled by the
 * speed of the underlying SharedPreferences.edit().commit() call which matches
 * previous performance.
 */
final class SharedPreferencesPersistentDataStore implements PersistentDataStore {
    // Single class-static executor for all persistence flushes across all instances.
    private static final Executor FLUSH_EXECUTOR = new ThreadPoolExecutor(
            0, 1,
            10L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new PersistenceThreadFactory());

    private final Application application;
    private final LDLogger logger;

    // Writes land here as resolved per-namespace state. Reads consult this map first.
    private final Map<String, State> pending = new HashMap<>();

    // Staging area for state currently being committed to disk. Populated by the flush
    // loop by moving entries from `pending` under the lock. Reads consult this map second.
    // Entries are removed as each namespace's commit succeeds.
    private final Map<String, State> committing = new HashMap<>();

    // Guards `pending`, `committing`, and `flushScheduled`. All state mutations happen
    // under this lock. Disk I/O (commit()) does NOT happen under this lock.
    private final Object lock = new Object();

    // True while a flush task is either queued or currently running on FLUSH_EXECUTOR.
    // Prevents redundant task submissions during a burst.
    private boolean flushScheduled = false;

    // Invoked by commitState immediately before Editor.commit(). Tests set
    // this to interpose behavior between "state moved into committing" and "commit to
    // disk begins" (e.g., to assert reads served by the committing layer). Volatile so
    // that all threads involved in test see same value.
    @VisibleForTesting
    volatile Runnable preCommitRunnable = null;

    public SharedPreferencesPersistentDataStore(Application application, LDLogger logger) {
        this.application = application;
        this.logger = logger;
    }

    public SharedPreferencesPersistentDataStore(Application application) {
        this(application, LDLogger.none());
    }

    @Override
    public String getValue(String storeNamespace, String key) {
        synchronized (lock) {
            // Layer resolution: a state's changes-map hit wins, otherwise a clear on that
            // layer short-circuits to null (the namespace is cleared as far as this layer
            // knows), otherwise we fall through to the next layer.
            State state = pending.get(storeNamespace);
            if (state != null) {
                if (state.changes.containsKey(key)) return state.changes.get(key);
                if (state.clear) return null;
            }
            state = committing.get(storeNamespace);
            if (state != null) {
                if (state.changes.containsKey(key)) return state.changes.get(key);
                if (state.clear) return null;
            }
        }
        // Neither layer knows about this key; fall through to shared preferences.
        SharedPreferences prefs = getSharedPreferences(storeNamespace);
        try {
            return prefs.getString(key, null);
        } catch (ClassCastException e) {
            try {
                // In the past, we sometimes stored numeric values directly as numbers via the
                // SharedPreferences API. Our new persistence model is simpler and expects strings.
                Long longValue = prefs.getLong(key, 0);
                return longValue == null ? null : String.valueOf(longValue);
            } catch (ClassCastException e1) {
                return null;
            }
        }
    }

    @Override
    public void setValue(String storeNamespace, String key, String value) {
        synchronized (lock) {
            State state = pending.get(storeNamespace);
            if (state == null) {
                state = new State();
                pending.put(storeNamespace, state);
            }
            if (state.changes == State.NO_CHANGES) {
                state.changes = new HashMap<>();
            }
            state.changes.put(key, value);
            // Do not touch state.clear or state.fullyDelete. A prior clear/fullyDelete stays
            // scheduled — it purges unknown data (and, for fullyDelete, the file itself)
            // before these writes recreate the file at commit time.
            scheduleFlushLocked();
        }
    }

    @Override
    public void setValues(String storeNamespace, Map<String, String> keysAndValues) {
        synchronized (lock) {
            State state = pending.get(storeNamespace);
            if (state == null) {
                state = new State();
                pending.put(storeNamespace, state);
            }
            if (state.changes == State.NO_CHANGES) {
                state.changes = new HashMap<>();
            }
            state.changes.putAll(keysAndValues);
            // Do not touch state.clear or state.fullyDelete. See setValue.
            scheduleFlushLocked();
        }
    }

    @Override
    public Collection<String> getKeys(String storeNamespace) {
        SharedPreferences prefs = getSharedPreferences(storeNamespace);
        Set<String> result = new HashSet<>(prefs.getAll().keySet());
        synchronized (lock) {
            // Apply committing first (older layer), then pending (newer layer).
            overlayState(result, committing.get(storeNamespace));
            overlayState(result, pending.get(storeNamespace));
        }
        return result;
    }

    private static void overlayState(Set<String> keys, State state) {
        if (state == null) return;
        if (state.clear) keys.clear();
        for (Map.Entry<String, String> e : state.changes.entrySet()) {
            if (e.getValue() == null) keys.remove(e.getKey());
            else keys.add(e.getKey());
        }
    }

    @Override
    public Collection<String> getAllNamespaces() {
        // Union of namespaces on disk and namespaces we have pending activity for.
        Set<String> result = new HashSet<>();

        File directory = new File(application.getFilesDir().getParent() + "/shared_prefs/");
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".xml")) {
                    result.add(file.getName().substring(0, file.getName().length() - 4));
                }
            }
        }

        synchronized (lock) {
            result.addAll(pending.keySet());
            result.addAll(committing.keySet());
        }

        return new ArrayList<>(result);
    }

    @Override
    public void clear(String storeNamespace, boolean fullyDelete) {
        // Replace any pending state for this namespace with a fresh clear state. Prior pending
        // writes are semantically superseded by clear and discarded. fullyDelete escalates
        // but never de-escalates: if a prior state had fullyDelete=true, we keep that intent
        // even if this call passes false.
        synchronized (lock) {
            State prior = pending.get(storeNamespace);
            boolean carriedFullyDelete = prior != null && prior.fullyDelete;
            State state = new State();
            state.clear = true;
            state.fullyDelete = fullyDelete || carriedFullyDelete;
            pending.put(storeNamespace, state);
            scheduleFlushLocked();
        }
    }

    /**
     * Blocks until every write requested against this store up to this call has been
     * committed to disk. Exposed with package-private visibility solely so that tests
     * can assert on disk state after asynchronous writes.
     */
    @VisibleForTesting
    void flushSynchronously() {
        final CountDownLatch latch = new CountDownLatch(1);
        FLUSH_EXECUTOR.execute(() -> {
            try {
                drainPending();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void scheduleFlushLocked() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        FLUSH_EXECUTOR.execute(this::drainPending);
    }

    /**
     * Loops: move pending into committing → commit each namespace's state → remove from
     * committing → check pending again. Exits when pending is empty. Each iteration
     * starts with {@code committing} empty, since we remove entries after every commit.
     */
    private void drainPending() {
        while (true) {
            Set<String> namespacesToCommit;
            synchronized (lock) {
                if (pending.isEmpty()) {
                    flushScheduled = false;
                    return;
                }
                committing.putAll(pending);
                pending.clear();
                namespacesToCommit = new HashSet<>(committing.keySet());
            }

            for (String namespace : namespacesToCommit) {
                State state;
                synchronized (lock) {
                    state = committing.get(namespace);
                }
                commitState(namespace, state);
                synchronized (lock) {
                    committing.remove(namespace);
                }
            }
        }
    }

    /**
     * Commit a single namespace's resolved state to disk.
     * <p>
     * If {@code state.fullyDelete} is set AND no writes are following the clear, the
     * XML file is removed after the commit. When writes accompany the clear, the file
     * must remain to hold them, so fullyDelete's file removal is superseded by those
     * writes — the net observable state (namespace contains only the new writes) is
     * unchanged.
     */
    private void commitState(String namespace, State state) {
        SharedPreferences.Editor editor = getSharedPreferences(namespace).edit();
        if (state.clear) {
            editor.clear();
        }
        for (Map.Entry<String, String> kv : state.changes.entrySet()) {
            if (kv.getValue() == null) {
                editor.remove(kv.getKey());
            } else {
                editor.putString(kv.getKey(), kv.getValue());
            }
        }

        // If a test has set a preCommitRunnable, run it now. Alternatives
        // required more invasive mocking.
        Runnable runnable = preCommitRunnable;
        if (runnable != null) {
            runnable.run();
        }

        try {
            editor.commit();
        } catch (Exception e) {
            logger.error("Encountered exception committing persistence for namespace '{}': {}",
                    namespace, e);
        }

        if (state.clear && state.fullyDelete && state.changes.isEmpty()) {
            File file = new File(application.getFilesDir().getParent()
                    + "/shared_prefs/" + namespace + ".xml");
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private SharedPreferences getSharedPreferences(String storeNamespace) {
        // Note, the Android API guarantees that whenever we call getSharedPreferences with the
        // same string, we receive the same object, so it is OK to make this call repeatedly
        // rather than caching the object.
        return application.getSharedPreferences(storeNamespace, Context.MODE_PRIVATE);
    }

    private static final class State {
        // Sentinel: shared immutable empty map used as the default `changes` value for performance.
        static final Map<String, String> NO_CHANGES = Collections.emptyMap();

        boolean clear = false;
        boolean fullyDelete = false;
        Map<String, String> changes = NO_CHANGES;
    }

    private static final class PersistenceThreadFactory implements java.util.concurrent.ThreadFactory {
        private static final AtomicInteger COUNTER = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "LaunchDarkly-SharedPreferencesPersistence-" +
                    COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
