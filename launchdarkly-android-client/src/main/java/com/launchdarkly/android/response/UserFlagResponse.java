package com.launchdarkly.android.response;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.EvaluationReason;

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
    private final boolean trackEvents;

    @Nullable
    private final Long debugEventsUntilDate;

    @Nullable
    private final EvaluationReason reason;

    public UserFlagResponse(@NonNull String key, @Nullable JsonElement value, int version, int flagVersion, @Nullable Integer variation, @Nullable Boolean trackEvents, @Nullable Long debugEventsUntilDate, @Nullable EvaluationReason reason) {
        this.key = key;
        this.value = value;
        this.version = version;
        this.flagVersion = flagVersion;
        this.variation = variation;
        this.trackEvents = trackEvents == null ? false : trackEvents.booleanValue();
        this.debugEventsUntilDate = debugEventsUntilDate;
        this.reason = reason;
    }

    public UserFlagResponse(String key, JsonElement value) {
        this(key, value, -1, -1, null, null, null, null);
    }

    public UserFlagResponse(String key, JsonElement value, int version, int flagVersion) {
        this(key, value, version, flagVersion, null, null, null, null);
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

    @Override
    public int getVersionForEvents() {
        return flagVersion > 0 ? flagVersion : version;
    }

    @Nullable
    @Override
    public Integer getVariation() {
        return variation;
    }

    @Override
    public boolean isTrackEvents() {
        return trackEvents;
    }

    @Nullable
    @Override
    public Long getDebugEventsUntilDate() {
        return debugEventsUntilDate;
    }

    @Nullable
    @Override
    public EvaluationReason getReason() {
        return reason;
    }

    @Override
    public JsonObject getAsJsonObject() {
        JsonObject object = new JsonObject();
        object.add("version", new JsonPrimitive(version));
        object.add("flagVersion", new JsonPrimitive(flagVersion));
        if (variation != null) {
            object.add("variation", new JsonPrimitive(variation));
        }
        if (trackEvents) {
            object.add("trackEvents", new JsonPrimitive(true));
        }
        if (debugEventsUntilDate != null) {
            object.add("debugEventsUntilDate", new JsonPrimitive(debugEventsUntilDate));
        }
        return object;
    }

    @Override
    public boolean isVersionMissing() {
        return version == -1;
    }
}
