package com.launchdarkly.android;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.gson.GsonCache;

import timber.log.Timber;

/**
 * Allows the client's flag evaluation methods to treat the various supported data types generically.
 */
abstract class ValueTypes {
    /**
     * Implements JSON serialization and deserialization for a specific type.
     * @param <T> the requested value type
     */
    public interface Converter<T> {
        /**
         * Converts a JSON value to the desired type. The JSON value is guaranteed to be non-null.
         * @param jsonValue the JSON value
         * @return the converted value, or null if the JSON value was not of the correct type
         */
        @Nullable public T valueFromJson(@NonNull JsonElement jsonValue);

        /**
         * Converts a value to JSON. The value is guaranteed to be non-null.
         * @param value the value
         * @return the JSON value
         */
        @NonNull public JsonElement valueToJson(@NonNull T value);
    }

    public static final Converter<Boolean> BOOLEAN = new Converter<Boolean>() {
        @Override
        public Boolean valueFromJson(JsonElement jsonValue) {
            return (jsonValue.isJsonPrimitive() && jsonValue.getAsJsonPrimitive().isBoolean()) ? jsonValue.getAsBoolean() : null;
        }

        @Override
        public JsonElement valueToJson(Boolean value) {
            return new JsonPrimitive(value);
        }
    };

    public static final Converter<Integer> INT = new Converter<Integer>() {
        @Override
        public Integer valueFromJson(JsonElement jsonValue) {
            return (jsonValue.isJsonPrimitive() && jsonValue.getAsJsonPrimitive().isNumber()) ? jsonValue.getAsInt() : null;
        }

        @Override
        public JsonElement valueToJson(Integer value) {
            return new JsonPrimitive(value);
        }
    };

    public static final Converter<Float> FLOAT = new Converter<Float>() {
        @Override
        public Float valueFromJson(JsonElement jsonValue) {
            return (jsonValue.isJsonPrimitive() && jsonValue.getAsJsonPrimitive().isNumber()) ? jsonValue.getAsFloat() : null;
        }

        @Override
        public JsonElement valueToJson(Float value) {
            return new JsonPrimitive(value);
        }
    };

    public static final Converter<String> STRING = new Converter<String>() {
        @Override
        public String valueFromJson(JsonElement jsonValue) {
            return (jsonValue.isJsonPrimitive() && jsonValue.getAsJsonPrimitive().isString()) ? jsonValue.getAsString() : null;
        }

        @Override
        public JsonElement valueToJson(String value) {
            return new JsonPrimitive(value);
        }
    };

    // Used for maintaining compatible behavior in allowing evaluation of Json flags as Strings
    // TODO(gwhelanld): remove in 3.0.0
    public static final Converter<String> STRINGCOMPAT = new Converter<String>() {
        @Override
        public String valueFromJson(JsonElement jsonValue) {
            if (jsonValue.isJsonPrimitive() && jsonValue.getAsJsonPrimitive().isString()) {
                return jsonValue.getAsString();
            } else if (!jsonValue.isJsonPrimitive() && !jsonValue.isJsonNull()) {
                Timber.w("JSON flag requested as String. For backwards compatibility " +
                        "returning a serialized representation of flag value. " +
                        "This behavior will be removed in the next major version (3.0.0)");
                return GsonCache.getGson().toJson(jsonValue);
            }
            return null;
        }

        @Override
        public JsonElement valueToJson(String value) {
            return new JsonPrimitive(value);
        }
    };

    public static final Converter<JsonElement> JSON = new Converter<JsonElement>() {
        @Override
        public JsonElement valueFromJson(JsonElement jsonValue) {
            return jsonValue;
        }

        @Override
        public JsonElement valueToJson(JsonElement value) {
            return value;
        }
    };
}
