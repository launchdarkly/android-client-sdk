package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;

class Flag {

    @NonNull
    private final String key;
    private final LDValue value;
    private final int version;
    private final Integer flagVersion;
    private final Integer variation;
    private final Boolean trackEvents;
    private final Boolean trackReason;
    private final Long debugEventsUntilDate;
    private final EvaluationReason reason;
    private final Boolean deleted;

    private Flag(@NonNull String key, LDValue value, int version, Integer flagVersion, Integer variation, Boolean trackEvents, Boolean trackReason, Long debugEventsUntilDate, EvaluationReason reason, boolean deleted) {
        this.key = key;
        this.value = value;
        this.version = version;
        this.flagVersion = flagVersion;
        this.variation = variation;
        this.trackEvents = trackEvents;
        this.trackReason = trackReason;
        this.debugEventsUntilDate = debugEventsUntilDate;
        this.reason = reason;
        this.deleted = deleted ? Boolean.valueOf(true) : null;
    }

    Flag(@NonNull String key, LDValue value, int version, Integer flagVersion, Integer variation, Boolean trackEvents, Boolean trackReason, Long debugEventsUntilDate, EvaluationReason reason) {
        this(key, value, version, flagVersion, variation, trackEvents, trackReason, debugEventsUntilDate, reason, false);
    }

    static Flag deletedItemPlaceholder(@NonNull String key, int version) {
        return new Flag(key, null, version, null, null, null, null, null, null, true);
    }

    String getKey() {
        return key;
    }

    @NonNull
    LDValue getValue() {
        // normalize() ensures that nulls become LDValue.ofNull() - Gson may give us nulls
        return LDValue.normalize(value);
    }

    int getVersion() {
        return version;
    }

    Integer getFlagVersion() {
        return flagVersion;
    }

    Integer getVariation() {
        return variation;
    }

    boolean isTrackEvents() {
        return trackEvents != null && trackEvents.booleanValue();
    }

    boolean isTrackReason() { return trackReason != null && trackReason.booleanValue(); }

    Long getDebugEventsUntilDate() {
        return debugEventsUntilDate;
    }

    EvaluationReason getReason() {
        return reason;
    }

    int getVersionForEvents() {
        return flagVersion == null ? version : flagVersion.intValue();
    }

    boolean isDeleted() {
        return deleted != null && deleted.booleanValue();
    }
}
