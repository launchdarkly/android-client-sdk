package com.launchdarkly.android.response.interpreter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.launchdarkly.android.response.FlagResponse;

import javax.annotation.Nullable;

/**
 * Farhan
 * 2018-01-30
 */
public class PatchFlagResponseInterpreter implements FlagResponseInterpreter<FlagResponse> {

    @Nullable
    @Override
    public FlagResponse apply(@Nullable JsonObject input) {
        if (input != null) {
            JsonElement keyElement = input.get("key");

            if (keyElement != null) {
                String key = keyElement.getAsJsonPrimitive().getAsString();
                return UserFlagResponseParser.parseFlag(input, key);
            }
        }
        return null;
    }
}
