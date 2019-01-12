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
public class PingFlagResponseInterpreter extends BaseFlagResponseInterpreter<List<FlagResponse>> {

    @NonNull
    @Override
    public List<FlagResponse> apply(@Nullable JsonObject input) {
        List<FlagResponse> flagResponseList = new ArrayList<>();
        if (input != null) {
            for (Map.Entry<String, JsonElement> entry : input.entrySet()) {
                String key = entry.getKey();
                JsonElement v = entry.getValue();

                if (isValueInsideObject(v)) {
                    JsonObject asJsonObject = v.getAsJsonObject();

                    Integer variation = getVariation(asJsonObject);
                    Boolean trackEvents = getTrackEvents(asJsonObject);
                    Long debugEventsUntilDate = getDebugEventsUntilDate(asJsonObject);


                    JsonElement flagVersionElement = asJsonObject.get("flagVersion");
                    JsonElement versionElement = asJsonObject.get("version");
                    int flagVersion = flagVersionElement != null && flagVersionElement.getAsJsonPrimitive().isNumber()
                            ? flagVersionElement.getAsInt()
                            : -1;
                    int version = versionElement != null && versionElement.getAsJsonPrimitive().isNumber()
                            ? versionElement.getAsInt()
                            : -1;
                    flagResponseList.add(new UserFlagResponse(key, asJsonObject.get("value"), version, flagVersion, variation, trackEvents, debugEventsUntilDate));
                } else {
                    flagResponseList.add(new UserFlagResponse(key, v));
                }
            }
        }
        return flagResponseList;
    }
}
