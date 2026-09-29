package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.Random;
import java.util.concurrent.ScheduledFuture;

/**
 * Wraps a synchronizer factory with availability state. A synchronizer is not usable while it
 * is waiting out a backoff after an unexpected error, or while it is blocked.
 * <p>
 * Package-private for internal use by FDv2DataSource. Callers synchronize on the
 * {@link SourceManager}'s lock.
 */
final class SynchronizerFactoryWithState {

    enum State {
        /** This synchronizer is available to use. */
        Available,
        /**
         * This synchronizer reported an unexpected error and is waiting out a backoff before it
         * may be used again.
         */
        BackingOff,
        /** This synchronizer is not available until something unblocks it. */
        Blocked
    }

    private final FDv2DataSource.DataSourceFactory<Synchronizer> factory;
    private State state = State.Available;
    private final boolean isFDv1Fallback;
    // Backoff after unexpected errors from this slot's synchronizers.
    private final StreamingRetryState retryState = new StreamingRetryState(
            RetryRegime.EXTENDED,
            RetryRegime.EXTENDED,
            StreamingRetryState.RESET_THRESHOLD_MILLIS,
            new Random());
    @Nullable
    private ScheduledFuture<?> pendingUnblock;
    @Nullable
    private String lastSynchronizerName;

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
     * Records healthy operation by this slot's synchronizer.
     *
     * @param nowMillis the current time in milliseconds, on the same clock as every other call on
     *                  this instance
     */
    void recordHealthy(long nowMillis) {
        retryState.recordSuccess(nowMillis);
    }

    /**
     * Records an unexpected error from this slot's synchronizer and puts the slot into backoff.
     *
     * @param synchronizerName the name of the synchronizer that failed, for logging
     * @param nowMillis        the current time in milliseconds, on the same clock as every other
     *                         call on this instance
     * @return how long the slot stays in backoff, in milliseconds
     */
    long startBackoff(@NonNull String synchronizerName, long nowMillis) {
        retryState.recordFailure(true, nowMillis);
        state = State.BackingOff;
        lastSynchronizerName = synchronizerName;
        return retryState.nextDelayMillis();
    }

    /**
     * Ends this slot's backoff, if it is in one.
     *
     * @return true if the slot became available
     */
    boolean endBackoff() {
        pendingUnblock = null;
        if (state != State.BackingOff) {
            return false;
        }
        state = State.Available;
        return true;
    }

    /**
     * @return the name of the synchronizer whose unexpected error started the current backoff,
     * or null if there has been none
     */
    @Nullable
    String getLastSynchronizerName() {
        return lastSynchronizerName;
    }

    void setPendingUnblock(@Nullable ScheduledFuture<?> pendingUnblock) {
        this.pendingUnblock = pendingUnblock;
    }

    void cancelPendingUnblock() {
        if (pendingUnblock != null) {
            pendingUnblock.cancel(false);
            pendingUnblock = null;
        }
    }
}
