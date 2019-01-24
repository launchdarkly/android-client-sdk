package com.launchdarkly.android.response;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Farhan
 * 2018-01-30
 */
public class UserFlagResponse implements FlagResponse {

    @NonNull
    private final String key;
    @Nullable
    private final JsonElement value;

    private final int version;

    private final int flagVersion;

    @Nullable
    private final Integer variation;

    @Nullable
    private final Boolean trackEvents;

    @Nullable
    private final Long debugEventsUntilDate;

    public UserFlagResponse(@NonNull String key, @Nullable JsonElement value, int version, int flagVersion, @Nullable Integer variation, @Nullable Boolean trackEvents, @Nullable Long debugEventsUntilDate) {
        this.key = key;
        this.value = value;
        this.version = version;
        this.flagVersion = flagVersion;
        this.variation = variation;
        this.trackEvents = trackEvents;
        this.debugEventsUntilDate = debugEventsUntilDate;
    }

    public UserFlagResponse(String key, JsonElement value) {
        this(key, value, -1, -1, null, null, null);
    }

    public UserFlagResponse(String key, JsonElement value, int version, int flagVersion) {
        this(key, value, version, flagVersion, null, null, null);
    }

    @NonNull
    @Override
    public String getKey() {
        return key;
    }

    @Nullable
    @Override
    public JsonElement getValue() {
        return value;
    }

    @Override
    public int getVersion() {
        return version;
    }

    @Override
    public int getFlagVersion() {
        return flagVersion;
    }

    @Nullable
    @Override
    public Integer getVariation() {
        return variation;
    }

    @Nullable
    @Override
    public Boolean getTrackEvents() {
        return trackEvents;
    }

    @Nullable
    @Override
    public Long getDebugEventsUntilDate() {
        return debugEventsUntilDate;
    }

    @Override
    public JsonObject getAsJsonObject() {
        JsonObject object = new JsonObject();
        object.add("version", new JsonPrimitive(version));
        object.add("flagVersion", new JsonPrimitive(flagVersion));
        object.add("variation", variation == null ? JsonNull.INSTANCE : new JsonPrimitive(variation));
        object.add("trackEvents", trackEvents == null ? JsonNull.INSTANCE : new JsonPrimitive(trackEvents));
        object.add("debugEventsUntilDate", debugEventsUntilDate == null ? JsonNull.INSTANCE : new JsonPrimitive(debugEventsUntilDate));
        return object;
    }

    @Override
    public boolean isVersionMissing() {
        return version == -1;
    }
}
