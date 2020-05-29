package com.launchdarkly.android;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import timber.log.Timber;

/**
 * Used internally by the SDK.
 */
class SharedPrefsSummaryEventStore implements SummaryEventStore {

    private final SharedPreferences sharedPreferences;

    SharedPrefsSummaryEventStore(Application application, String name) {
        this.sharedPreferences = application.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @Override
    public synchronized void addOrUpdateEvent(String flagResponseKey, JsonElement value, JsonElement defaultVal, int version, @Nullable Integer nullableVariation) {
        JsonElement variation = nullableVariation == null ? JsonNull.INSTANCE : new JsonPrimitive(nullableVariation);
        JsonObject object = getValueAsJsonObject(flagResponseKey);
        if (object == null) {
            object = createNewEvent(value, defaultVal, version, variation);
        } else {
            JsonArray countersArray = object.get("counters").getAsJsonArray();

            boolean isUnknown = version == -1;
            boolean variationExists = false;
            for (JsonElement element : countersArray) {
                if (element instanceof JsonObject) {
                    JsonObject asJsonObject = element.getAsJsonObject();
                    boolean unknownElement = asJsonObject.get("unknown") != null && !asJsonObject.get("unknown").equals(JsonNull.INSTANCE) && asJsonObject.get("unknown").getAsBoolean();

                    if (unknownElement != isUnknown) {
                        continue;
                    }
                    // Both are unknown and same value
                    if (isUnknown && LDUtil.objectsEqual(value, asJsonObject.get("value"))) {
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
                addNewCountersElement(countersArray, value, version, variation);
            }
        }

        if (sharedPreferences.getAll().isEmpty()) {
            object.add("startDate", new JsonPrimitive(System.currentTimeMillis()));
        }

        String flagSummary = object.toString();

        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(flagResponseKey, object.toString());
        editor.apply();

        Timber.d("Updated summary for flagKey %s to %s", flagResponseKey, flagSummary);
    }

    @Override
    public synchronized SummaryEvent getSummaryEvent() {
        return getSummaryEventNoSync();
    }

    private SummaryEvent getSummaryEventNoSync() {
        JsonObject features = getFeaturesJsonObject();
        if (features.keySet().size() == 0) {
            return null;
        }
        Long startDate = null;
        for (String key : features.keySet()) {
            JsonObject asJsonObject = features.get(key).getAsJsonObject();
            if (asJsonObject.has("startDate")) {
                startDate = asJsonObject.get("startDate").getAsLong();
                asJsonObject.remove("startDate");
                break;
            }
        }
        SummaryEvent summaryEvent = new SummaryEvent(startDate, System.currentTimeMillis(), features);
        Timber.d("Sending Summary Event: %s", summaryEvent.toString());
        return summaryEvent;
    }

    @Override
    public synchronized SummaryEvent getSummaryEventAndClear() {
        SummaryEvent summaryEvent = getSummaryEventNoSync();
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        editor.apply();
        return summaryEvent;
    }

    private JsonObject createNewEvent(JsonElement value, JsonElement defaultVal, int version, JsonElement variation) {
        JsonObject object = new JsonObject();
        object.add("default", defaultVal);
        JsonArray countersArray = new JsonArray();
        addNewCountersElement(countersArray, value, version, variation);
        object.add("counters", countersArray);
        return object;
    }

    private void addNewCountersElement(JsonArray countersArray, @Nullable JsonElement value, int version, JsonElement variation) {
        JsonObject newCounter = new JsonObject();
        if (version == -1) {
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


    private JsonObject getFeaturesJsonObject() {
        JsonObject returnObject = new JsonObject();
        for (String key : sharedPreferences.getAll().keySet()) {
            returnObject.add(key, getValueAsJsonObject(key));
        }
        return returnObject;
    }

    @SuppressLint("ApplySharedPref")
    @Nullable
    private JsonObject getValueAsJsonObject(String flagResponseKey) {
        String storedFlag;
        try {
            storedFlag = sharedPreferences.getString(flagResponseKey, null);
        } catch (ClassCastException castException) {
            // An old version of shared preferences is stored, so clear it.
            // The flag responses will get re-synced with the server
            sharedPreferences.edit().clear().commit();
            return null;
        }

        if (storedFlag == null) {
            return null;
        }

        JsonElement element = new JsonParser().parse(storedFlag);
        if (element instanceof JsonObject) {
            return (JsonObject) element;
        }

        return null;
    }

    public synchronized void clear() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        editor.apply();
    }

}
