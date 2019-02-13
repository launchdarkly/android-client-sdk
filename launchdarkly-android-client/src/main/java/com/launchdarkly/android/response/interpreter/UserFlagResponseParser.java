package com.launchdarkly.android.response.interpreter;

import android.support.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.response.UserFlagResponse;

public class UserFlagResponseParser {

    public static UserFlagResponse parseFlag(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonPrimitive versionElement = getPrimitive(o, "version");
        JsonPrimitive valueElement = getPrimitive(o, "value");
        JsonPrimitive flagVersionElement = getPrimitive(o, "flagVersion");
        JsonPrimitive variationElement = getPrimitive(o, "variation");
        JsonPrimitive trackEventsElement = getPrimitive(o, "trackEvents");
        JsonPrimitive debugEventsUntilDateElement = getPrimitive(o, "debugEventsUntilDate");
        int version = versionElement != null && versionElement.isNumber()
                ? versionElement.getAsInt()
                : -1;
        Integer variation = variationElement != null && variationElement.isNumber()
                ? variationElement.getAsInt()
                : null;
        int flagVersion = flagVersionElement != null && flagVersionElement.isNumber()
                ? flagVersionElement.getAsInt()
                : -1;
        boolean trackEvents = trackEventsElement != null && trackEventsElement.isBoolean()
                && trackEventsElement.getAsBoolean();
        Long debugEventsUntilDate = debugEventsUntilDateElement != null && debugEventsUntilDateElement.isNumber()
                ? debugEventsUntilDateElement.getAsLong()
                : null;
        return new UserFlagResponse(key, valueElement, version, flagVersion, variation, trackEvents, debugEventsUntilDate);
    }

    @Nullable
    private static JsonPrimitive getPrimitive(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
    }
}
