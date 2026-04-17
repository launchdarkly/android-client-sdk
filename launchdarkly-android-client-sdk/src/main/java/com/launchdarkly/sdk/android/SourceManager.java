package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

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
    private final List<Initializer> initializers;

    private final Object activeSourceLock = new Object();
    private Closeable activeSource;
    private boolean isShutdown = false;

    /** Start at -1 so the first getNext* increments to 0. */
    private int synchronizerIndex = -1;
    private int initializerIndex = -1;

    private SynchronizerFactoryWithState currentSynchronizerFactory;

    SourceManager(
            @NonNull List<SynchronizerFactoryWithState> synchronizerFactories,
            @NonNull List<Initializer> initializers
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

    /** Block the current synchronizer so it will not be returned again (e.g. after TERMINAL_ERROR). */
    void blockCurrentSynchronizer() {
        synchronized (activeSourceLock) {
            if (currentSynchronizerFactory != null) {
                currentSynchronizerFactory.block();
            }
        }
    }

    boolean isCurrentSynchronizerFDv1Fallback() {
        synchronized (activeSourceLock) {
            return currentSynchronizerFactory != null && currentSynchronizerFactory.isFDv1Fallback();
        }
    }

    /**
     * Get the next pre-built initializer whose {@link Initializer#isRequiredBeforeStartup()}
     * matches the given value, set it as active (closing any previous active source),
     * and return it. Returns null if shutdown or no more matching initializers.
     * <p>
     * Call with {@code true} for the eager pass (pre-startup), then
     * {@link #resetInitializerIndex()}, then call with {@code false} for the deferred pass.
     *
     * @param isRequiredBeforeStartup filter value to match against each initializer
     */
    Initializer getNextInitializerAndSetActive(boolean isRequiredBeforeStartup) {
        synchronized (activeSourceLock) {
            if (isShutdown) {
                return null;
            }
            while (initializerIndex + 1 < initializers.size()) {
                initializerIndex++;
                Initializer init = initializers.get(initializerIndex);
                if (init.isRequiredBeforeStartup() == isRequiredBeforeStartup) {
                    if (activeSource != null) {
                        safeClose(activeSource);
                    }
                    activeSource = init;
                    return init;
                }
            }
            return null;
        }
    }

    /**
     * Reset the initializer index to -1 so the next call to
     * {@link #getNextInitializerAndSetActive(boolean)} re-scans from the beginning.
     * Used between the eager and deferred initializer passes.
     */
    void resetInitializerIndex() {
        synchronized (activeSourceLock) {
            initializerIndex = -1;
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
