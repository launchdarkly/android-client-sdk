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
    private final boolean fdv1Fallback;
    private final long fdv1FallbackTtlSeconds;

    private FDv2SourceResult(@NonNull SourceResultType resultType,
                             @Nullable ChangeSet<Map<String, DataModel.Flag>> changeSet,
                             @Nullable Status status,
                             boolean fdv1Fallback,
                             long fdv1FallbackTtlSeconds) {
        this.resultType = resultType;
        this.changeSet = changeSet;
        this.status = status;
        this.fdv1Fallback = fdv1Fallback;
        this.fdv1FallbackTtlSeconds = fdv1FallbackTtlSeconds;
    }

    @NonNull
    public static FDv2SourceResult changeSet(@NonNull ChangeSet<Map<String, DataModel.Flag>> changeSet, boolean fdv1Fallback) {
        return changeSet(changeSet, fdv1Fallback, 0);
    }

    /**
     * Creates a change set result.
     *
     * @param changeSet              the change set
     * @param fdv1Fallback           true if the server directed the SDK to fall back to FDv1
     * @param fdv1FallbackTtlSeconds the fallback TTL supplied by the server, in seconds, or 0 if
     *                               none was supplied
     * @return the result
     */
    @NonNull
    public static FDv2SourceResult changeSet(
            @NonNull ChangeSet<Map<String, DataModel.Flag>> changeSet,
            boolean fdv1Fallback,
            long fdv1FallbackTtlSeconds) {
        return new FDv2SourceResult(SourceResultType.CHANGE_SET, changeSet, null, fdv1Fallback, fdv1FallbackTtlSeconds);
    }

    @NonNull
    public static FDv2SourceResult status(@NonNull Status status, boolean fdv1Fallback) {
        return status(status, fdv1Fallback, 0);
    }

    /**
     * Creates a status result.
     *
     * @param status                 the status
     * @param fdv1Fallback           true if the server directed the SDK to fall back to FDv1
     * @param fdv1FallbackTtlSeconds the fallback TTL supplied by the server, in seconds, or 0 if
     *                               none was supplied
     * @return the result
     */
    @NonNull
    public static FDv2SourceResult status(
            @NonNull Status status,
            boolean fdv1Fallback,
            long fdv1FallbackTtlSeconds) {
        return new FDv2SourceResult(SourceResultType.STATUS, null, status, fdv1Fallback, fdv1FallbackTtlSeconds);
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

    /**
     * True if the server indicated that the SDK should fall back to FDv1 data sources,
     * via the {@code x-ld-fd-fallback} response header or a goodbye event.
     */
    public boolean isFdv1Fallback() {
        return fdv1Fallback;
    }

    /**
     * The fallback TTL supplied by the server, in seconds, via the {@code x-ld-fd-fallback-ttl}
     * response header or a goodbye event's {@code protocolFallbackTTL}. This is the raw value; 0
     * means none was supplied. It is only meaningful when {@link #isFdv1Fallback()} is true.
     */
    public long getFdv1FallbackTtlSeconds() {
        return fdv1FallbackTtlSeconds;
    }
}
