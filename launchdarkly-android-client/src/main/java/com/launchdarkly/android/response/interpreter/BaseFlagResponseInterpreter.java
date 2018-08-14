package com.launchdarkly.android.response.interpreter;

import android.support.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Created by jamesthacker on 4/10/18.
 */

public abstract class BaseFlagResponseInterpreter<T> implements FlagResponseInterpreter<T> {

    public boolean isValueInsideObject(JsonElement element) {
        return !element.isJsonNull() && element.isJsonObject() && element.getAsJsonObject().has("value");
    }

    @Nullable
    public Long getDebugEventsUntilDate(JsonObject object) {
        if (object == null || object.get("debugEventsUntilDate") == null || object.get("debugEventsUntilDate").isJsonNull()) {
            return null;
        }
        return object.get("debugEventsUntilDate").getAsLong();
    }

    @Nullable
    public Boolean getTrackEvents(JsonObject object) {
        if (object == null || object.get("trackEvents") == null || object.get("trackEvents").isJsonNull()) {
            return null;
        }
        return object.get("trackEvents").getAsBoolean();
    }

    @Nullable
    public Integer getVariation(JsonObject object) {
        if (object == null || object.get("variation") == null || object.get("variation").isJsonNull()) {
            return null;
        }
        return object.get("variation").getAsInt();
    }
}
