package com.launchdarkly.android;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.gson.JsonElement;
import com.launchdarkly.android.value.LDValue;

/**
 * Allows the client's flag evaluation methods to treat the various supported data types generically.
 */
abstract class ValueTypes {
    /**
     * Implements LDValue conversion for a specific type.
     * @param <T> the requested value type
     */
    interface Converter<T> {
        /**
         * Converts an LDValue to the desired type. The LDValue is guaranteed to be non-null.
         * @param ldValue the JSON value
         * @return the converted value, or null if the JSON value was not of the correct type
         */
        @Nullable
        T extractValue(@NonNull LDValue ldValue);

        /**
         * Converts a value to an LDValue. The value is guaranteed to be non-null.
         * @param value the value
         * @return the JSON value
         */
        @NonNull
        LDValue embedValue(@NonNull T value);
    }

    static final Converter<Boolean> BOOLEAN = new Converter<Boolean>() {
        @Override
        public Boolean extractValue(@NonNull LDValue ldValue) {
            return (ldValue.isBoolean() ? ldValue.booleanValue() : null);
        }

        @NonNull
        @Override
        public LDValue embedValue(@NonNull Boolean value) {
            return LDValue.of(value);
        }
    };

    static final Converter<Integer> INT = new Converter<Integer>() {
        @Override
        public Integer extractValue(@NonNull LDValue ldValue) {
            return (ldValue.isNumber() ? ldValue.intValue() : null);
        }

        @NonNull
        @Override
        public LDValue embedValue(@NonNull Integer value) {
            return LDValue.of(value);
        }
    };

    static final Converter<Float> FLOAT = new Converter<Float>() {
        @Override
        public Float extractValue(@NonNull LDValue ldValue) {
            return (ldValue.isNumber() ? ldValue.floatValue() : null);
        }

        @NonNull
        @Override
        public LDValue embedValue(@NonNull Float value) {
            return LDValue.of(value);
        }
    };

    static final Converter<Double> DOUBLE = new Converter<Double>() {
        @Override
        public Double extractValue(@NonNull LDValue ldValue) {
            return (ldValue.isNumber() ? ldValue.doubleValue() : null);
        }

        @NonNull
        @Override
        public LDValue embedValue(@NonNull Double value) {
            return LDValue.of(value);
        }
    };

    static final Converter<String> STRING = new Converter<String>() {
        @Override
        public String extractValue(@NonNull LDValue ldValue) {
            return ldValue.stringValue();
        }

        @NonNull
        @Override
        public LDValue embedValue(@NonNull String value) {
            return LDValue.of(value);
        }
    };

    static final Converter<JsonElement> JSON = new Converter<JsonElement>() {
        @Override
        public JsonElement extractValue(@NonNull LDValue ldValue) {
            //noinspection deprecation
            return ldValue.asJsonElement();
        }

        @NonNull
        @Override
        public LDValue embedValue(@NonNull JsonElement value) {
            //noinspection deprecation
            return LDValue.fromJsonElement(value);
        }
    };

    static final Converter<LDValue> LDVALUE = new Converter<LDValue>() {
        @Override
        public LDValue extractValue(@NonNull LDValue ldValue) {
            return ldValue;
        }

        @NonNull
        @Override
        public LDValue embedValue(@NonNull LDValue value) {
            return value;
        }
    };
}
