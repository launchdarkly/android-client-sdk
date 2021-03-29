package com.launchdarkly.android;

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
        @Expose Integer version;
        @Expose Integer variation;
        @Expose Boolean unknown;
        @Expose LDValue value;
        @Expose int count;

        FlagCounter(LDValue value, Integer version, Integer variation) {
            this.version = version;
            this.variation = variation;
            if (version == null) {
                unknown = true;
            }
            this.value = value;
            this.count = 1;
        }

        boolean isUnknown() {
            return unknown != null && unknown;
        }

        boolean matches(Integer version, Integer variation) {
            if (isUnknown()) {
                return version == null;
            }

            return Objects.equals(this.version, version) &&
                    Objects.equals(this.variation, variation);
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
