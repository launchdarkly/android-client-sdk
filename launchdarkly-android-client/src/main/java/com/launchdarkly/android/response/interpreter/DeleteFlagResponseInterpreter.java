package com.launchdarkly.android.response.interpreter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.launchdarkly.android.response.FlagResponse;
import com.launchdarkly.android.response.UserFlagResponse;

import javax.annotation.Nullable;

/**
 * Farhan
 * 2018-01-30
 */
public class DeleteFlagResponseInterpreter extends BaseFlagResponseInterpreter<FlagResponse> {

    @Nullable
    @Override
    public FlagResponse apply(@Nullable JsonObject input) {
        if (input != null) {
            JsonElement keyElement = input.get("key");
            JsonElement valueElement = input.get("value");
            JsonElement versionElement = input.get("version");
            JsonElement flagVersionElement = input.get("flagVersion");
            Boolean trackEvents = getTrackEvents(input);
            Long debugEventsUntilDate = getDebugEventsUntilDate(input);
            int version = versionElement != null && versionElement.getAsJsonPrimitive().isNumber()
                    ? versionElement.getAsInt()
                    : -1;
            Integer variation = getVariation(input);
            int flagVersion = flagVersionElement != null && flagVersionElement.getAsJsonPrimitive().isNumber()
                    ? flagVersionElement.getAsInt()
                    : -1;

            if (keyElement != null) {
                String key = keyElement.getAsJsonPrimitive().getAsString();
                return new UserFlagResponse(key, valueElement, version, flagVersion, variation, trackEvents, debugEventsUntilDate);
            }
        }
        return null;
    }
}
