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
 * Port of java-core SourceManager. Package-private for internal use by FDv2DataSource.
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

    /** Block all non-FDv1 synchronizers and unblock the FDv1 fallback. Android: no-op for now. */
    void fdv1Fallback() {
        for (SynchronizerFactoryWithState s : synchronizerFactories) {
            if (s.isFDv1Fallback()) {
                s.unblock();
            } else {
                s.block();
            }
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
