package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.DataModel;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;

import java.util.Map;

/**
 * Result type for FDv2 initializers and synchronizers. An initializer produces a single result;
 * a synchronizer produces a stream of results.
 *
 * @see Initializer
 * @see Synchronizer
 */
public final class FDv2SourceResult {

    /**
     * Status payload for STATUS results.
     */
    public static final class Status {
        @NonNull
        private final SourceSignal state;
        @Nullable
        private final Throwable error;
        @Nullable
        private final String reason;

        private Status(@NonNull SourceSignal state, @Nullable Throwable error, @Nullable String reason) {
            this.state = state;
            this.error = error;
            this.reason = reason;
        }

        public static Status goodbye(@Nullable String reason) {
            return new Status(SourceSignal.GOODBYE, null, reason);
        }

        public static Status interrupted(@Nullable Throwable error) {
            return new Status(SourceSignal.INTERRUPTED, error, null);
        }

        public static Status terminalError(@Nullable Throwable error) {
            return new Status(SourceSignal.TERMINAL_ERROR, error, null);
        }

        public static Status shutdown() {
            return new Status(SourceSignal.SHUTDOWN, null, null);
        }

        @NonNull
        public SourceSignal getState() {
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

    private final SourceResultType resultType;
    @Nullable
    private final ChangeSet<Map<String, DataModel.Flag>> changeSet;
    @Nullable
    private final Status status;

    private FDv2SourceResult(@NonNull SourceResultType resultType, @Nullable ChangeSet<Map<String, DataModel.Flag>> changeSet, @Nullable Status status) {
        this.resultType = resultType;
        this.changeSet = changeSet;
        this.status = status;
    }

    @NonNull
    public static FDv2SourceResult changeSet(@NonNull ChangeSet<Map<String, DataModel.Flag>> changeSet) {
        return new FDv2SourceResult(SourceResultType.CHANGE_SET, changeSet, null);
    }

    @NonNull
    public static FDv2SourceResult status(@NonNull Status status) {
        return new FDv2SourceResult(SourceResultType.STATUS, null, status);
    }

    @NonNull
    public SourceResultType getResultType() {
        return resultType;
    }

    @Nullable
    public ChangeSet<Map<String, DataModel.Flag>> getChangeSet() {
        return changeSet;
    }

    @Nullable
    public Status getStatus() {
        return status;
    }
}
