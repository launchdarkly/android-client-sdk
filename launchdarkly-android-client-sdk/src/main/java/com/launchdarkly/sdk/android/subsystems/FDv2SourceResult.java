package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Result type for FDv2 initializers and synchronizers. An initializer produces a single result;
 * a synchronizer produces a stream of results.
 *
 * @see Initializer
 * @see Synchronizer
 */
public final class FDv2SourceResult {

    /**
     * State of the source when emitting a status result.
     */
    public enum State {
        /** Temporary interruption; the source may reconnect. Not typically used with initializers. */
        INTERRUPTED,
        /** The source has been shut down and will not produce further results. */
        SHUTDOWN,
        /** Terminal error; the source will not produce further results. */
        TERMINAL_ERROR,
        /** The source has been instructed to disconnect (e.g. server sent goodbye). */
        GOODBYE,
    }

    /**
     * Whether this result carries a change set or a status update.
     */
    public enum ResultType {
        /** The source emitted a change set. */
        CHANGE_SET,
        /** The source emitted a status (interruption, shutdown, error, goodbye). */
        STATUS,
    }

    /**
     * Status payload for STATUS results.
     */
    public static final class Status {
        private final State state;
        @Nullable
        private final Throwable error;
        @Nullable
        private final String reason;

        private Status(State state, @Nullable Throwable error, @Nullable String reason) {
            this.state = state;
            this.error = error;
            this.reason = reason;
        }

        public static Status goodbye(@Nullable String reason) {
            return new Status(State.GOODBYE, null, reason);
        }

        public static Status interrupted(@Nullable Throwable error) {
            return new Status(State.INTERRUPTED, error, null);
        }

        public static Status terminalError(@Nullable Throwable error) {
            return new Status(State.TERMINAL_ERROR, error, null);
        }

        public static Status shutdown() {
            return new Status(State.SHUTDOWN, null, null);
        }

        @NonNull
        public State getState() {
            return state;
        }

        @Nullable
        public Throwable getError() {
            return error;
        }

        /** For GOODBYE, the reason if provided; otherwise null. */
        @Nullable
        public String getReason() {
            return reason;
        }
    }

    private final ResultType resultType;
    @Nullable
    private final ChangeSet changeSet;
    @Nullable
    private final Status status;

    private FDv2SourceResult(ResultType resultType, @Nullable ChangeSet changeSet, @Nullable Status status) {
        this.resultType = resultType;
        this.changeSet = changeSet;
        this.status = status;
    }

    public static FDv2SourceResult changeSet(@NonNull ChangeSet changeSet) {
        return new FDv2SourceResult(ResultType.CHANGE_SET, changeSet, null);
    }

    public static FDv2SourceResult status(@NonNull Status status) {
        return new FDv2SourceResult(ResultType.STATUS, null, status);
    }

    @NonNull
    public ResultType getResultType() {
        return resultType;
    }

    @Nullable
    public ChangeSet getChangeSet() {
        return changeSet;
    }

    @Nullable
    public Status getStatus() {
        return status;
    }
}
