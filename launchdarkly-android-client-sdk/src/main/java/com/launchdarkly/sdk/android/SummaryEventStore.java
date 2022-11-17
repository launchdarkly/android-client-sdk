package com.launchdarkly.sdk.android;

import androidx.annotation.Nullable;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.launchdarkly.sdk.LDValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

interface SummaryEventStore {
    void clear();
    void addOrUpdateEvent(String flagResponseKey, LDValue value, LDValue defaultVal, @Nullable Integer version, @Nullable Integer variation);
    SummaryEvent getSummaryEvent();
    SummaryEvent getSummaryEventAndClear();

    class FlagCounter {
        @Expose final Integer version;
        @Expose final Integer variation;
        @Expose final Boolean unknown;
        @Expose final LDValue value;
        @Expose final int count;

        FlagCounter(LDValue value, Integer version, Integer variation, int count) {
            this.version = version;
            this.variation = variation;
            unknown = version == null ? Boolean.TRUE : null;
            this.value = value;
            this.count = count;
        }
    }

    class FlagCounters {
        @Expose @SerializedName("default") LDValue defaultValue;
        @Expose List<FlagCounter> counters = new ArrayList<>();

        FlagCounters(LDValue defaultValue) {
            this.defaultValue = defaultValue;
        }
    }
}
