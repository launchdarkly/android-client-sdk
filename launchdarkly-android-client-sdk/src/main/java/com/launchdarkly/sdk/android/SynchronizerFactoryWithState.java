package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.Synchronizer;

/**
 * Wraps a synchronizer factory with availability state (available/blocked).
 * Used by {@link SourceManager} to skip synchronizers that have been blocked (e.g. after TERMINAL_ERROR).
 * <p>
 * Package-private for internal use by FDv2DataSource.
 */
final class SynchronizerFactoryWithState {

    enum State {
        /** This synchronizer is available to use. */
        Available,
        /** This synchronizer is no longer available (e.g. after TERMINAL_ERROR). */
        Blocked
    }

    private final FDv2DataSource.DataSourceFactory<Synchronizer> factory;
    private State state = State.Available;
    private final boolean isFDv1Fallback;

    SynchronizerFactoryWithState(@NonNull FDv2DataSource.DataSourceFactory<Synchronizer> factory) {
        this(factory, false);
    }

    SynchronizerFactoryWithState(@NonNull FDv2DataSource.DataSourceFactory<Synchronizer> factory, boolean isFDv1Fallback) {
        this.factory = factory;
        this.isFDv1Fallback = isFDv1Fallback;
    }

    State getState() {
        return state;
    }

    void block() {
        state = State.Blocked;
    }

    void unblock() {
        state = State.Available;
    }

    Synchronizer build() {
        return factory.build();
    }

    boolean isFDv1Fallback() {
        return isFDv1Fallback;
    }
}
