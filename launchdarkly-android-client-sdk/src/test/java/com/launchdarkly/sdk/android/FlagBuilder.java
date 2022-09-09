package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;

public class FlagBuilder {

    @NonNull
    private String key;
    private LDValue value = null;
    private int version = 0;
    private Integer flagVersion = null;
    private Integer variation = null;
    private Boolean trackEvents = null;
    private Boolean trackReason = null;
    private Long debugEventsUntilDate = null;
    private EvaluationReason reason = null;

    public FlagBuilder(@NonNull String key) {
        this.key = key;
    }

    public FlagBuilder value(LDValue value) {
        this.value = value;
        return this;
    }

    public FlagBuilder value(boolean value) {
        return value(LDValue.of(value));
    }

    public FlagBuilder version(int version) {
        this.version = version;
        return this;
    }

    public FlagBuilder flagVersion(Integer flagVersion) {
        this.flagVersion = flagVersion;
        return this;
    }

    public FlagBuilder variation(Integer variation) {
        this.variation = variation;
        return this;
    }

    public FlagBuilder trackEvents(Boolean trackEvents) {
        this.trackEvents = trackEvents;
        return this;
    }

    public FlagBuilder trackReason(Boolean trackReason) {
        this.trackReason = trackReason;
        return this;
    }

    public FlagBuilder debugEventsUntilDate(Long debugEventsUntilDate) {
        this.debugEventsUntilDate = debugEventsUntilDate;
        return this;
    }

    public FlagBuilder reason(EvaluationReason reason) {
        this.reason = reason;
        return this;
    }

    public Flag build() {
        return new Flag(key, value, version, flagVersion, variation, trackEvents, trackReason, debugEventsUntilDate, reason);
    }
}
