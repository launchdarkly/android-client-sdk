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
public class DeleteFlagResponseInterpreter implements FlagResponseInterpreter<FlagResponse> {

    @Nullable
    @Override
    public FlagResponse apply(@Nullable JsonObject input) {
        if (input != null) {
            JsonElement keyElement = input.get("key");
            JsonElement valueElement = input.get("value");
            JsonElement versionElement = input.get("version");

            if (keyElement != null) {
                String key = keyElement.getAsJsonPrimitive().getAsString();
                if (versionElement != null && versionElement.getAsJsonPrimitive().isNumber()) {
                    float version = versionElement.getAsFloat();
                    return new UserFlagResponse(key, valueElement, version);
                } else {
                    return new UserFlagResponse(key, valueElement);
                }

            }
        }
        return null;
    }
}
