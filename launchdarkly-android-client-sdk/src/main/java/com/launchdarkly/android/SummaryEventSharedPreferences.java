package com.launchdarkly.android;

import android.support.annotation.Nullable;

import com.google.gson.JsonElement;

/**
 * Used internally by the SDK.
 */
public interface SummaryEventSharedPreferences {

    void clear();
    void addOrUpdateEvent(String flagResponseKey, JsonElement value, JsonElement defaultVal, int version, @Nullable Integer variation);
    SummaryEvent getSummaryEvent();
    SummaryEvent getSummaryEventAndClear();
}
