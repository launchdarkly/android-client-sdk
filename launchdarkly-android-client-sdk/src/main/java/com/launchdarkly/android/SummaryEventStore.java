package com.launchdarkly.android;

import androidx.annotation.Nullable;

import com.launchdarkly.sdk.LDValue;

interface SummaryEventStore {
    void clear();
    void addOrUpdateEvent(String flagResponseKey, LDValue value, LDValue defaultVal, int version, @Nullable Integer variation);
    SummaryEvent getSummaryEvent();
    SummaryEvent getSummaryEventAndClear();
}
