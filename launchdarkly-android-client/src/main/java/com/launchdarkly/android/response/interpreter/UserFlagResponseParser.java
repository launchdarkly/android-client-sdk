package com.launchdarkly.android.response.interpreter;

import android.support.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.launchdarkly.android.response.UserFlagResponse;

public class UserFlagResponseParser {

    @Nullable
    public static Long getDebugEventsUntilDate(JsonObject object) {
        if (object == null || object.get("debugEventsUntilDate") == null || object.get("debugEventsUntilDate").isJsonNull()) {
            return null;
        }
        return object.get("debugEventsUntilDate").getAsLong();
    }

    @Nullable
    public static Boolean getTrackEvents(JsonObject object) {
        if (object == null || object.get("trackEvents") == null || object.get("trackEvents").isJsonNull()) {
            return null;
        }
        return object.get("trackEvents").getAsBoolean();
    }

    @Nullable
    public static Integer getVariation(JsonObject object) {
        if (object == null || object.get("variation") == null || object.get("variation").isJsonNull()) {
            return null;
        }
        return object.get("variation").getAsInt();
    }

    public static UserFlagResponse parseFlag(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement versionElement = o.get("version");
        JsonElement valueElement = o.get("value");
        JsonElement flagVersionElement = o.get("flagVersion");
        Boolean trackEvents = getTrackEvents(o);
        Long debugEventsUntilDate = getDebugEventsUntilDate(o);
        int version = versionElement != null && versionElement.getAsJsonPrimitive().isNumber()
                ? versionElement.getAsInt()
                : -1;
        Integer variation = getVariation(o);
        int flagVersion = flagVersionElement != null && flagVersionElement.getAsJsonPrimitive().isNumber()
                ? flagVersionElement.getAsInt()
                : -1;
        JsonElement reasonElement = o.get("reason");
        return new UserFlagResponse(key, valueElement, version, flagVersion, variation, trackEvents, debugEventsUntilDate);
    }
}
