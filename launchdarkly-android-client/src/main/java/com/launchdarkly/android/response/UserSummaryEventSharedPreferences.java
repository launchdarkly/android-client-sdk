package com.launchdarkly.android.response;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Created by jamesthacker on 4/12/18.
 */

public class UserSummaryEventSharedPreferences extends BaseUserSharedPreferences implements SummaryEventSharedPreferences {

    public UserSummaryEventSharedPreferences(Application application, String name) {
        this.sharedPreferences = application.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @Override
    public void addOrUpdateEvent(String flagResponseKey, JsonElement value, JsonElement defaultVal, int version, @Nullable Integer nullableVariation, boolean isUnknown) {
        JsonElement variation = nullableVariation == null ? JsonNull.INSTANCE : new JsonPrimitive(nullableVariation);
        JsonObject object = getValueAsJsonObject(flagResponseKey);
        if (object == null) {
            object = createNewEvent(value, defaultVal, version, variation, isUnknown);
        } else {
            JsonArray countersArray = object.get("counters").getAsJsonArray();

            boolean variationExists = false;
            for (JsonElement element : countersArray) {
                if (element instanceof JsonObject) {
                    JsonObject asJsonObject = element.getAsJsonObject();
                    boolean unknownElement = asJsonObject.get("unknown") != null && !asJsonObject.get("unknown").equals(JsonNull.INSTANCE) && asJsonObject.get("unknown").getAsBoolean();

                    if (unknownElement != isUnknown) {
                        continue;
                    }
                    // Both are unknown and same value
                    if (isUnknown && value.equals(asJsonObject.get("value"))) {
                        variationExists = true;
                        int currentCount = asJsonObject.get("count").getAsInt();
                        asJsonObject.add("count", new JsonPrimitive(++currentCount));
                        break;
                    }
                    JsonElement variationElement = asJsonObject.get("variation");
                    JsonElement versionElement = asJsonObject.get("version");

                    // We can compare variation rather than value.
                    boolean isSameVersion = versionElement != null && asJsonObject.get("version").getAsInt() == version;
                    boolean isSameVariation = variationElement != null && variationElement.equals(variation);
                    if (isSameVersion && isSameVariation) {
                        variationExists = true;
                        int currentCount = asJsonObject.get("count").getAsInt();
                        asJsonObject.add("count", new JsonPrimitive(++currentCount));
                        break;
                    }
                }
            }

            if (!variationExists) {
                addNewCountersElement(countersArray, value, version, variation, isUnknown);
            }
        }

        if (sharedPreferences.getAll().isEmpty()) {
            object.add("startDate", new JsonPrimitive(System.currentTimeMillis()));
        }

        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(flagResponseKey, object.toString());
        editor.apply();
    }

    private JsonObject createNewEvent(JsonElement value, JsonElement defaultVal, int version, JsonElement variation, boolean isUnknown) {
        JsonObject object = new JsonObject();
        object.add("default", defaultVal);
        JsonArray countersArray = new JsonArray();
        addNewCountersElement(countersArray, value, version, variation, isUnknown);
        object.add("counters", countersArray);
        return object;
    }

    private void addNewCountersElement(JsonArray countersArray, @Nullable JsonElement value, int version, JsonElement variation, boolean isUnknown) {
        JsonObject newCounter = new JsonObject();
        if (isUnknown) {
            newCounter.add("unknown", new JsonPrimitive(true));
            newCounter.add("value", value);
        } else {
            newCounter.add("value", value);
            newCounter.add("version", new JsonPrimitive(version));
            newCounter.add("variation", variation);
        }
        newCounter.add("count", new JsonPrimitive(1));
        countersArray.add(newCounter);
    }

    @Override
    public JsonObject getFeaturesJsonObject() {
        JsonObject returnObject = new JsonObject();
        for (String key : sharedPreferences.getAll().keySet()) {
            returnObject.add(key, getValueAsJsonObject(key));
        }
        return returnObject;
    }
}
