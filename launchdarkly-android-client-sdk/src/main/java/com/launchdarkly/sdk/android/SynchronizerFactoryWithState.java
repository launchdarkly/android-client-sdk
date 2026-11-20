package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.subsystems.Synchronizer;

/**
 * Wraps a synchronizer factory with availability state (available/blocked) and park state.
 * Used by {@link SourceManager} to skip synchronizers that are blocked or parked.
 * <p>
 * Package-private for internal use by FDv2DataSource.
 */
final class SynchronizerFactoryWithState {

    enum State {
        /** This synchronizer is available to use. */
        Available,
        /** This synchronizer is no longer available to use. */
        Blocked
    }

    private final FDv2DataSource.DataSourceFactory<Synchronizer> factory;
    private State state = State.Available;
    private final boolean isFDv1Fallback;

    /**
     * The number of unexpected failures since the synchronizer last reported that it met its reset threshold.
     */
    private int parkCount = 0;

    /**
     * The time at which the synchronizer stops being parked, or null if it is not parked.
     */
    private MonotonicTime unparkTime = null;

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

    /**
     * Records an unexpected failure.
     *
     * @return the park count, including this failure
     */
    int incrementParkCount() {
        return ++parkCount;
    }

    void resetParkCount() {
        parkCount = 0;
    }

    void park(@NonNull MonotonicTime unparkTime) {
        this.unparkTime = unparkTime;
    }

    /**
     * The time at which the synchronizer stops being parked.
     *
     * @return the unpark time, or null if the synchronizer is not parked
     */
    @Nullable
    MonotonicTime getUnparkTime() {
        return unparkTime;
    }

    /**
     * Stops parking the synchronizer if its unpark time has arrived.
     *
     * @param now the current time
     */
    void unparkIfExpired(@NonNull MonotonicTime now) {
        if (unparkTime != null && !now.isBefore(unparkTime)) {
            unparkTime = null;
        }
    }

    /**
     * True if the synchronizer is available and is not parked.
     *
     * @return true if the synchronizer can be started
     */
    boolean isEligible() {
        return state == State.Available && unparkTime == null;
    }
}
