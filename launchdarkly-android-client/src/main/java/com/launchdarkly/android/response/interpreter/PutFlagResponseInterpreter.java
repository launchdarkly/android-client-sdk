package com.launchdarkly.android.response.interpreter;

import android.support.annotation.NonNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.launchdarkly.android.response.FlagResponse;
import com.launchdarkly.android.response.UserFlagResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Farhan
 * 2018-01-30
 */
public class PutFlagResponseInterpreter implements FlagResponseInterpreter<List<FlagResponse>> {

    @NonNull
    @Override
    public List<FlagResponse> apply(@Nullable JsonObject input) {
        List<FlagResponse> flagResponseList = new ArrayList<>();
        if (input != null) {
            for (Map.Entry<String, JsonElement> entry : input.entrySet()) {
                JsonElement v = entry.getValue();
                String key = entry.getKey();
                JsonObject asJsonObject = v.getAsJsonObject();
                if (asJsonObject != null) {
                    JsonElement versionElement = asJsonObject.get("version");
                    JsonElement valueElement = asJsonObject.get("value");
                    if (versionElement != null && versionElement.getAsJsonPrimitive().isNumber()) {
                        float version = versionElement.getAsJsonPrimitive().getAsFloat();
                        flagResponseList.add(new UserFlagResponse(key, valueElement, version));
                    }
                }
            }
        }
        return flagResponseList;
    }
}
