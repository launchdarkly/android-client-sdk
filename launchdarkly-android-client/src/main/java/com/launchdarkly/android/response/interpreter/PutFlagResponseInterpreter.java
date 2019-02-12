package com.launchdarkly.android.response.interpreter;

import android.support.annotation.NonNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.launchdarkly.android.response.FlagResponse;

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
                    flagResponseList.add(UserFlagResponseParser.parseFlag(asJsonObject, key));
                }
            }
        }
        return flagResponseList;
    }
}
