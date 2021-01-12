package com.launchdarkly.android;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.Objects;
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
                    if (isUnknown && Objects.equals(value, asJsonObject.get("value"))) {
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

        sharedPreferences.edit()
            .putString(flagResponseKey, object.toString())
            .apply();

        Timber.d("Updated summary for flagKey %s to %s", flagResponseKey, flagSummary);
    }

    @Override
    public synchronized SummaryEvent getSummaryEvent() {
        return getSummaryEventNoSync();
    }

    private static Long removeStartDateFromFeatureSummary(@NonNull JsonObject featureSummary) {
        JsonElement startDate = featureSummary.remove("startDate");
        if (startDate != null && startDate.isJsonPrimitive()) {
            try {
                return startDate.getAsJsonPrimitive().getAsLong();
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private SummaryEvent getSummaryEventNoSync() {
        Long startDate = null;
        JsonObject features = new JsonObject();
        for (String key : sharedPreferences.getAll().keySet()) {
            JsonObject featureSummary = getValueAsJsonObject(key);
            if (featureSummary != null) {
                Long featureStartDate = removeStartDateFromFeatureSummary(featureSummary);
                if (featureStartDate != null) {
                    startDate = featureStartDate;
                }
                features.add(key, featureSummary);
            }
        }

        if (startDate == null) {
            return null;
        }

        SummaryEvent summaryEvent = new SummaryEvent(startDate, System.currentTimeMillis(), features);
        Timber.d("Sending Summary Event: %s", summaryEvent.toString());
        return summaryEvent;
    }

    @Override
    public synchronized SummaryEvent getSummaryEventAndClear() {
        SummaryEvent summaryEvent = getSummaryEventNoSync();
        clear();
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
        newCounter.add("value", value);
        if (version == -1) {
            newCounter.add("unknown", new JsonPrimitive(true));
        } else {
            newCounter.add("version", new JsonPrimitive(version));
            newCounter.add("variation", variation);
        }
        newCounter.add("count", new JsonPrimitive(1));
        countersArray.add(newCounter);
    }

    @SuppressLint("ApplySharedPref")
    @Nullable
    private JsonObject getValueAsJsonObject(String flagResponseKey) {
        try {
            String storedFlag = sharedPreferences.getString(flagResponseKey, null);
            if (storedFlag == null) {
                return null;
            }
            JsonElement element = JsonParser.parseString(storedFlag);
            if (element instanceof JsonObject) {
                return element.getAsJsonObject();
            }
        } catch (ClassCastException | JsonParseException ignored) {
            // Fallthrough to clear
        }
        // An old version of shared preferences is stored, so clear it.
        sharedPreferences.edit().clear().commit();
        return null;
    }

    public synchronized void clear() {
        sharedPreferences.edit().clear().apply();
    }
}
