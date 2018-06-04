package com.launchdarkly.android.response;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Farhan
 * 2018-01-30
 */
public interface FlagResponse {

    String getKey();

    JsonElement getValue();

    int getVersion();

    int getFlagVersion();

    Integer getVariation();

    Boolean getTrackEvents();

    Long getDebugEventsUntilDate();

    JsonObject getAsJsonObject();

    boolean isVersionMissing();
}
