package com.launchdarkly.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;

class Flag implements FlagUpdate {

    @NonNull
    private final String key;
    private final LDValue value;
    private final Integer version;
    private final Integer flagVersion;
    private final Integer variation;
    private final Boolean trackEvents;
    private final Boolean trackReason;
    private final Long debugEventsUntilDate;
    private final EvaluationReason reason;

    Flag(@NonNull String key, LDValue value, Integer version, Integer flagVersion, Integer variation, Boolean trackEvents, Boolean trackReason, Long debugEventsUntilDate, EvaluationReason reason) {
        this.key = key;
        this.value = value;
        this.version = version;
        this.flagVersion = flagVersion;
        this.variation = variation;
        this.trackEvents = trackEvents;
        this.trackReason = trackReason;
        this.debugEventsUntilDate = debugEventsUntilDate;
        this.reason = reason;
    }

    @NonNull
    String getKey() {
        return key;
    }

    @NonNull
    LDValue getValue() {
        // normalize() ensures that nulls become LDValue.ofNull() - Gson may give us nulls
        return LDValue.normalize(value);
    }

    Integer getVersion() {
        return version;
    }

    Integer getFlagVersion() {
        return flagVersion;
    }

    Integer getVariation() {
        return variation;
    }

    boolean getTrackEvents() {
        return trackEvents == null ? false : trackEvents;
    }

    boolean isTrackReason() { return trackReason == null ? false : trackReason; }

    Long getDebugEventsUntilDate() {
        return debugEventsUntilDate;
    }

    EvaluationReason getReason() {
        return reason;
    }

    boolean isVersionMissing() {
        return version == null;
    }

    Integer getVersionForEvents() {
        if (flagVersion == null) {
            return version;
        }
        return flagVersion;
    }

    @Override
    public Flag updateFlag(Flag before) {
        if (before == null || this.isVersionMissing() || before.isVersionMissing() || this.getVersion() > before.getVersion()) {
            return this;
        }
        return before;
    }

    @Override
    public String flagToUpdate() {
        return key;
    }
}
