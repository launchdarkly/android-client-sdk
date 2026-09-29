package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages the state of synchronizers and initializers: tracks which is active,
 * advances through the lists (skipping synchronizers that are blocked or backing off),
 * and closes the previous source when switching.
 * <p>
 * A synchronizer that reports an unexpected error is put into a backoff, and its slot is skipped
 * until the backoff ends. No synchronizer is ever permanently removed.
 * <p>
 * Package-private for internal use by FDv2DataSource.
 */
final class SourceManager implements Closeable {

    private final List<SynchronizerFactoryWithState> synchronizerFactories;
    private final List<FDv2DataSource.DataSourceFactory<Initializer>> initializers;
    private final ScheduledExecutorService executor;

    private final Object activeSourceLock = new Object();
    private Closeable activeSource;
    private boolean isShutdown = false;

    /** Start at -1 so the first getNext* increments to 0. */
    private int synchronizerIndex = -1;
    private int initializerIndex = -1;

    private SynchronizerFactoryWithState currentSynchronizerFactory;

    // Completed and replaced whenever a slot's backoff ends or this manager closes.
    private LDAwaitFuture<Void> availabilityChanged = new LDAwaitFuture<>();

    SourceManager(
            @NonNull List<SynchronizerFactoryWithState> synchronizerFactories,
            @NonNull List<FDv2DataSource.DataSourceFactory<Initializer>> initializers,
            @NonNull ScheduledExecutorService executor
    ) {
        this.synchronizerFactories = synchronizerFactories;
        this.initializers = initializers;
        this.executor = executor;
    }

    /**
     * Reset the synchronizer index to -1 so the next call starts from the first available.
     * Used when recovering to the prime synchronizer.
     */
    void resetSourceIndex() {
        synchronized (activeSourceLock) {
            synchronizerIndex = -1;
        }
    }

    /** True if any synchronizer is marked as FDv1 fallback. */
    boolean hasFDv1Fallback() {
        for (SynchronizerFactoryWithState s : synchronizerFactories) {
            if (s.isFDv1Fallback()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Block all non-FDv1 synchronizers, unblock the FDv1 fallback, and reset the
     * synchronizer index so the next {@link #getNextAvailableSynchronizerAndSetActive()}
     * picks the now-unblocked FDv1 slot.
     */
    void fdv1Fallback() {
        synchronized (activeSourceLock) {
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                if (s.isFDv1Fallback()) {
                    s.unblock();
                } else {
                    s.cancelPendingUnblock();
                    s.block();
                }
            }
            synchronizerIndex = -1;
        }
    }

    private SynchronizerFactoryWithState getNextAvailableSynchronizer() {
        SynchronizerFactoryWithState candidate = null;
        int visited = 0;
        while (visited < synchronizerFactories.size()) {
            synchronizerIndex++;
            if (synchronizerIndex >= synchronizerFactories.size()) {
                synchronizerIndex = 0;
            }
            SynchronizerFactoryWithState c = synchronizerFactories.get(synchronizerIndex);
            if (c.getState() == SynchronizerFactoryWithState.State.Available) {
                candidate = c;
                break;
            }
            visited++;
        }
        return candidate;
    }

    /**
     * Get the next available synchronizer, build it, set it as active (closing any previous active source),
     * and return it. Returns null if shutdown or no available synchronizers.
     * Skips synchronizers whose factory returns null from build().
     */
    Synchronizer getNextAvailableSynchronizerAndSetActive() {
        synchronized (activeSourceLock) {
            if (isShutdown) {
                currentSynchronizerFactory = null;
                return null;
            }
            int limit = synchronizerFactories.size();
            int tried = 0;
            while (tried < limit) {
                SynchronizerFactoryWithState factoryWithState = getNextAvailableSynchronizer();
                if (factoryWithState == null) {
                    currentSynchronizerFactory = null;
                    return null;
                }
                tried++;
                Synchronizer synchronizer = factoryWithState.build();
                if (synchronizer == null) {
                    continue;
                }
                currentSynchronizerFactory = factoryWithState;
                if (activeSource != null) {
                    safeClose(activeSource);
                }
                activeSource = synchronizer;
                return synchronizer;
            }
            currentSynchronizerFactory = null;
            return null;
        }
    }

    boolean hasAvailableSources() {
        return hasInitializers() || getAvailableSynchronizerCount() > 0;
    }

    boolean hasInitializers() {
        return !initializers.isEmpty();
    }

    boolean hasAvailableSynchronizers() {
        return getAvailableSynchronizerCount() > 0;
    }

    private FDv2DataSource.DataSourceFactory<Initializer> getNextInitializer() {
        initializerIndex++;
        if (initializerIndex >= initializers.size()) {
            return null;
        }
        return initializers.get(initializerIndex);
    }

    /**
     * Puts the current synchronizer's slot into backoff. The slot is skipped by
     * {@link #getNextAvailableSynchronizerAndSetActive()} until the backoff ends.
     *
     * @param synchronizerName the name of the synchronizer that failed, for logging
     * @param nowMillis        the current time in milliseconds, on the same clock as every other
     *                         call on this manager
     * @return how long the slot stays in backoff, in milliseconds, or -1 if there is no current
     * synchronizer
     */
    long backOffCurrentSynchronizer(@NonNull String synchronizerName, long nowMillis) {
        synchronized (activeSourceLock) {
            final SynchronizerFactoryWithState slot = currentSynchronizerFactory;
            if (slot == null || isShutdown) {
                return -1;
            }
            long delayMillis = slot.startBackoff(synchronizerName, nowMillis);
            ScheduledFuture<?> unblock = executor.schedule(new Runnable() {
                @Override
                public void run() {
                    endBackoff(slot);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
            slot.setPendingUnblock(unblock);
            return delayMillis;
        }
    }

    /**
     * Records healthy operation by the current synchronizer, which counts toward resetting its
     * slot's backoff.
     *
     * @param nowMillis the current time in milliseconds, on the same clock as every other call on
     *                  this manager
     */
    void recordCurrentSynchronizerHealthy(long nowMillis) {
        synchronized (activeSourceLock) {
            if (currentSynchronizerFactory != null) {
                currentSynchronizerFactory.recordHealthy(nowMillis);
            }
        }
    }

    /**
     * Ends a slot's backoff and wakes any caller waiting in {@link #awaitAvailabilityChange()}.
     *
     * @return the name of the synchronizer whose error started the backoff, if the slot became
     * available, or null if nothing changed
     */
    @Nullable
    String endBackoff(@NonNull SynchronizerFactoryWithState slot) {
        LDAwaitFuture<Void> toComplete;
        String name;
        synchronized (activeSourceLock) {
            if (isShutdown || !slot.endBackoff()) {
                return null;
            }
            name = slot.getLastSynchronizerName();
            toComplete = availabilityChanged;
            availabilityChanged = new LDAwaitFuture<>();
        }
        toComplete.set(null);
        return name == null ? "" : name;
    }

    /** True if any synchronizer is waiting out a backoff. Always false once closed. */
    boolean hasBackingOffSynchronizers() {
        synchronized (activeSourceLock) {
            if (isShutdown) {
                return false;
            }
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                if (s.getState() == SynchronizerFactoryWithState.State.BackingOff) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * @return a future that completes the next time a slot's backoff ends, or when this manager
     * closes
     */
    Future<Void> awaitAvailabilityChange() {
        synchronized (activeSourceLock) {
            return availabilityChanged;
        }
    }

    /**
     * @return true if a synchronizer earlier in the list than the current one is available
     */
    boolean hasAvailableSynchronizerBeforeCurrent() {
        synchronized (activeSourceLock) {
            for (int i = 0; i < synchronizerIndex && i < synchronizerFactories.size(); i++) {
                if (synchronizerFactories.get(i).getState() == SynchronizerFactoryWithState.State.Available) {
                    return true;
                }
            }
            return false;
        }
    }

    boolean isCurrentSynchronizerFDv1Fallback() {
        synchronized (activeSourceLock) {
            return currentSynchronizerFactory != null && currentSynchronizerFactory.isFDv1Fallback();
        }
    }

    /**
     * Get the next initializer, build it, set it as active (closing any previous active source),
     * and return it. Returns null if shutdown or no more initializers.
     * Skips initializers whose factory returns null from build().
     */
    Initializer getNextInitializerAndSetActive() {
        synchronized (activeSourceLock) {
            if (isShutdown) {
                return null;
            }
            while (true) {
                FDv2DataSource.DataSourceFactory<Initializer> factory = getNextInitializer();
                if (factory == null) {
                    return null;
                }
                Initializer initializer = factory.build();
                if (initializer != null) {
                    if (activeSource != null) {
                        safeClose(activeSource);
                    }
                    activeSource = initializer;
                    return initializer;
                }
            }
        }
    }

    /**
     * True if the current synchronizer is the prime one, meaning that no synchronizer before it
     * in the list is available or backing off.
     */
    boolean isPrimeSynchronizer() {
        synchronized (activeSourceLock) {
            for (int i = 0; i < synchronizerFactories.size(); i++) {
                if (synchronizerFactories.get(i).getState() != SynchronizerFactoryWithState.State.Blocked) {
                    return synchronizerIndex == i;
                }
            }
            return false;
        }
    }

    int getAvailableSynchronizerCount() {
        synchronized (activeSourceLock) {
            int count = 0;
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                if (s.getState() == SynchronizerFactoryWithState.State.Available) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * @return the number of synchronizers that are available or backing off
     */
    int getUsableSynchronizerCount() {
        synchronized (activeSourceLock) {
            int count = 0;
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                if (s.getState() != SynchronizerFactoryWithState.State.Blocked) {
                    count++;
                }
            }
            return count;
        }
    }

    @Override
    public void close() {
        LDAwaitFuture<Void> toComplete;
        synchronized (activeSourceLock) {
            isShutdown = true;
            if (activeSource != null) {
                safeClose(activeSource);
                activeSource = null;
            }
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                s.cancelPendingUnblock();
            }
            toComplete = availabilityChanged;
        }
        toComplete.set(null);
    }

    private static void safeClose(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // We are done with this source; ignore close errors.
        }
    }
}
