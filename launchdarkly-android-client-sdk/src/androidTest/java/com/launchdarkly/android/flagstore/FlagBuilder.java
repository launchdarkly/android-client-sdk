package com.launchdarkly.android.flagstore;

import android.support.annotation.NonNull;

import com.google.gson.JsonElement;
import com.launchdarkly.android.EvaluationReason;

public class FlagBuilder {

    @NonNull
    private String key;
    private JsonElement value = null;
    private Integer version = null;
    private Integer flagVersion = null;
    private Integer variation = null;
    private Boolean trackEvents = null;
    private Long debugEventsUntilDate = null;
    private EvaluationReason reason = null;

    public FlagBuilder(@NonNull String key) {
        this.key = key;
    }

    public FlagBuilder value(JsonElement value) {
        this.value = value;
        return this;
    }

    public FlagBuilder version(Integer version) {
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

    public FlagBuilder debugEventsUntilDate(Long debugEventsUntilDate) {
        this.debugEventsUntilDate = debugEventsUntilDate;
        return this;
    }

    public FlagBuilder reason(EvaluationReason reason) {
        this.reason = reason;
        return this;
    }

    public Flag build() {
        return new Flag(key, value, version, flagVersion, variation, trackEvents, debugEventsUntilDate, reason);
    }
}
