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
            JsonElement versionElement = input.get("version");
            int version = versionElement != null && versionElement.getAsJsonPrimitive().isNumber()
                    ? versionElement.getAsInt()
                    : -1;

            if (keyElement != null) {
                String key = keyElement.getAsJsonPrimitive().getAsString();
                return new UserFlagResponse(key, null, version, -1, -1, false, null, null);
            }
        }
        return null;
    }
}
