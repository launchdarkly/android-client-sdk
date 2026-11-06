package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Manages the state of synchronizers and initializers: tracks which is active,
 * advances through the lists (with optional block state for synchronizers),
 * and closes the previous source when switching.
 * <p>
 * Package-private for internal use by FDv2DataSource.
 */
final class SourceManager implements Closeable {

    private final List<SynchronizerFactoryWithState> synchronizerFactories;
    private final List<FDv2DataSource.DataSourceFactory<Initializer>> initializers;

    private final Object activeSourceLock = new Object();
    private Closeable activeSource;
    private boolean isShutdown = false;

    /** Start at -1 so the first getNext* increments to 0. */
    private int synchronizerIndex = -1;
    private int initializerIndex = -1;

    private SynchronizerFactoryWithState currentSynchronizerFactory;

    /**
     * The time at which the fallback to FDv1 ends, or null if the SDK is not falling back to FDv1. Accessed only
     * under activeSourceLock.
     */
    private MonotonicTime fdv1FallbackEndTime;

    SourceManager(
            @NonNull List<SynchronizerFactoryWithState> synchronizerFactories,
            @NonNull List<FDv2DataSource.DataSourceFactory<Initializer>> initializers
    ) {
        this.synchronizerFactories = synchronizerFactories;
        this.initializers = initializers;
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

    /** True if any synchronizer is marked as FDv1 fallback (Android: not used yet). */
    boolean hasFDv1Fallback() {
        for (SynchronizerFactoryWithState s : synchronizerFactories) {
            if (s.isFDv1Fallback()) {
                return true;
            }
        }
        return false;
    }

    boolean hasFDv2Synchronizers() {
        for (SynchronizerFactoryWithState s : synchronizerFactories) {
            if (!s.isFDv1Fallback()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Block all non-FDv1 synchronizers, unblock the FDv1 fallback, and reset the
     * synchronizer index so the next {@link #getNextAvailableSynchronizerAndSetActive()}
     * picks the now-unblocked FDv1 slot. The fallback lasts until the given time.
     *
     * @param endTime the time at which the fallback to FDv1 ends
     */
    void startFDv1Fallback(@NonNull MonotonicTime endTime) {
        synchronized (activeSourceLock) {
            fdv1FallbackEndTime = endTime;
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                if (s.isFDv1Fallback()) {
                    s.unblock();
                } else {
                    s.block();
                }
            }
            synchronizerIndex = -1;
        }
    }

    /**
     * The time at which the fallback to FDv1 ends.
     *
     * @return the end time, or null if the SDK is not falling back to FDv1
     */
    @Nullable
    MonotonicTime getFDv1FallbackEndTime() {
        synchronized (activeSourceLock) {
            return fdv1FallbackEndTime;
        }
    }

    /**
     * Ends the fallback to FDv1 if its end time has arrived. This unblocks the FDv2 synchronizers, blocks the FDv1
     * fallback, and starts the search for the next synchronizer from the most-preferred one.
     *
     * @return true if the fallback ended
     */
    boolean endFDv1FallbackIfExpired() {
        synchronized (activeSourceLock) {
            if (fdv1FallbackEndTime == null || MonotonicTime.now().isBefore(fdv1FallbackEndTime)) {
                return false;
            }
            fdv1FallbackEndTime = null;
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                if (s.isFDv1Fallback()) {
                    s.block();
                } else {
                    s.unblock();
                }
            }
            synchronizerIndex = -1;
            return true;
        }
    }

    /**
     * Stops parking every synchronizer whose unpark time has arrived.
     */
    void unparkExpiredSynchronizers() {
        synchronized (activeSourceLock) {
            MonotonicTime now = MonotonicTime.now();
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                s.unparkIfExpired(now);
            }
        }
    }

    private SynchronizerFactoryWithState getNextAvailableSynchronizer() {
        SynchronizerFactoryWithState candidate = null;
        unparkExpiredSynchronizers();
        int visited = 0;
        while (visited < synchronizerFactories.size()) {
            synchronizerIndex++;
            if (synchronizerIndex >= synchronizerFactories.size()) {
                synchronizerIndex = 0;
            }
            SynchronizerFactoryWithState c = synchronizerFactories.get(synchronizerIndex);
            if (c.isEligible()) {
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
                // Set before the build, so that a build that throws leaves this synchronizer current.
                currentSynchronizerFactory = factoryWithState;
                Synchronizer synchronizer = factoryWithState.build();
                if (synchronizer == null) {
                    continue;
                }
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
     * Records an unexpected failure of the current synchronizer.
     *
     * @return the park count of the current synchronizer, including this failure
     */
    int incrementCurrentSynchronizerParkCount() {
        synchronized (activeSourceLock) {
            return currentSynchronizerFactory.incrementParkCount();
        }
    }

    void resetCurrentSynchronizerParkCount() {
        synchronized (activeSourceLock) {
            currentSynchronizerFactory.resetParkCount();
        }
    }

    void parkCurrentSynchronizer(@NonNull MonotonicTime unparkTime) {
        synchronized (activeSourceLock) {
            currentSynchronizerFactory.park(unparkTime);
        }
    }

    /**
     * The earliest unpark time of the available synchronizers.
     *
     * @return the earliest unpark time, or null if no available synchronizer is parked
     */
    @Nullable
    MonotonicTime getEarliestUnparkTime() {
        synchronized (activeSourceLock) {
            MonotonicTime earliest = null;
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                MonotonicTime unparkTime = s.getUnparkTime();
                if (s.getState() == SynchronizerFactoryWithState.State.Available
                        && unparkTime != null
                        && (earliest == null || unparkTime.isBefore(earliest))) {
                    earliest = unparkTime;
                }
            }
            return earliest;
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
     * True if a synchronizer other than the current one is available.
     *
     * @return true if another synchronizer is available
     */
    boolean hasOtherAvailableSynchronizer() {
        synchronized (activeSourceLock) {
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                if (s != currentSynchronizerFactory && s.isEligible()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * True if a synchronizer earlier in the list than the current one is available.
     *
     * @return true if a more-preferred synchronizer is available
     */
    boolean hasMorePreferredAvailableSynchronizer() {
        synchronized (activeSourceLock) {
            for (SynchronizerFactoryWithState s : synchronizerFactories) {
                if (s == currentSynchronizerFactory) {
                    return false;
                }
                if (s.isEligible()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** True if the current synchronizer is the first available one (prime). */
    boolean isPrimeSynchronizer() {
        synchronized (activeSourceLock) {
            for (int i = 0; i < synchronizerFactories.size(); i++) {
                if (synchronizerFactories.get(i).getState() == SynchronizerFactoryWithState.State.Available) {
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
     * Closes the active source, if there is one.
     */
    void closeActiveSource() {
        synchronized (activeSourceLock) {
            if (activeSource != null) {
                safeClose(activeSource);
                activeSource = null;
            }
        }
    }

    @Override
    public void close() {
        synchronized (activeSourceLock) {
            isShutdown = true;
            if (activeSource != null) {
                safeClose(activeSource);
                activeSource = null;
            }
        }
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
