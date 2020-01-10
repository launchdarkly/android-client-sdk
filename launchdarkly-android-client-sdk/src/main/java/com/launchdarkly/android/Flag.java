package com.launchdarkly.android;

import android.support.annotation.NonNull;

import com.google.gson.JsonElement;

class Flag implements FlagUpdate, FlagInterface {

    @NonNull
    private final String key;
    private final JsonElement value;
    private final Integer version;
    private final Integer flagVersion;
    private final Integer variation;
    private final Boolean trackEvents;
    private final Boolean trackReason;
    private final Long debugEventsUntilDate;
    private final EvaluationReason reason;

    @Deprecated
    public Flag(@NonNull String key, JsonElement value, Integer version, Integer flagVersion, Integer variation, Boolean trackEvents, Long debugEventsUntilDate, EvaluationReason reason) {
        this(key, value, version, flagVersion, variation, trackEvents, null, debugEventsUntilDate, reason);
    }

    public Flag(@NonNull String key, JsonElement value, Integer version, Integer flagVersion, Integer variation, Boolean trackEvents, Boolean trackReason, Long debugEventsUntilDate, EvaluationReason reason) {
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
    public String getKey() {
        return key;
    }

    public JsonElement getValue() {
        return value;
    }

    public Integer getVersion() {
        return version;
    }

    public Integer getFlagVersion() {
        return flagVersion;
    }

    public Integer getVariation() {
        return variation;
    }

    public boolean getTrackEvents() {
        return trackEvents == null ? false : trackEvents;
    }

    public boolean isTrackReason() { return trackReason == null ? false : trackReason; }

    public Long getDebugEventsUntilDate() {
        return debugEventsUntilDate;
    }

    @Override
    public EvaluationReason getReason() {
        return reason;
    }

    public boolean isVersionMissing() {
        return version == null;
    }

    public int getVersionForEvents() {
        if (flagVersion == null) {
            return version == null ? -1 : version;
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
