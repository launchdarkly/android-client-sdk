package com.launchdarkly.android.value;

import java.util.HashMap;
import java.util.Map;

/**
 * A builder created by {@link LDValue#buildObject()}. Builder methods are not thread-safe.
 *
 * @since 4.8.0
 */
public final class ObjectBuilder {
    private volatile Map<String, LDValue> builder = new HashMap<>();
    private volatile boolean copyOnWrite = false;

    /**
     * Sets a key-value pair in the builder, overwriting any previous value for that key.
     *
     * @param key   a string key
     * @param value a value
     * @return the same builder
     */
    public ObjectBuilder put(String key, LDValue value) {
        if (copyOnWrite) {
            builder = new HashMap<>(builder);
            copyOnWrite = false;
        }
        builder.put(key, value);
        return this;
    }

    /**
     * Sets a key-value pair in the builder, overwriting any previous value for that key.
     *
     * @param key   a string key
     * @param value a value
     * @return the same builder
     */
    public ObjectBuilder put(String key, boolean value) {
        return put(key, LDValue.of(value));
    }

    /**
     * Sets a key-value pair in the builder, overwriting any previous value for that key.
     *
     * @param key   a string key
     * @param value a value
     * @return the same builder
     */
    public ObjectBuilder put(String key, int value) {
        return put(key, LDValue.of(value));
    }

    /**
     * Sets a key-value pair in the builder, overwriting any previous value for that key.
     *
     * @param key   a string key
     * @param value a value
     * @return the same builder
     */
    public ObjectBuilder put(String key, long value) {
        return put(key, LDValue.of(value));
    }

    /**
     * Sets a key-value pair in the builder, overwriting any previous value for that key.
     *
     * @param key   a string key
     * @param value a value
     * @return the same builder
     */
    public ObjectBuilder put(String key, float value) {
        return put(key, LDValue.of(value));
    }

    /**
     * Sets a key-value pair in the builder, overwriting any previous value for that key.
     *
     * @param key   a string key
     * @param value a value
     * @return the same builder
     */
    public ObjectBuilder put(String key, double value) {
        return put(key, LDValue.of(value));
    }

    /**
     * Sets a key-value pair in the builder, overwriting any previous value for that key.
     *
     * @param key   a string key
     * @param value a value
     * @return the same builder
     */
    public ObjectBuilder put(String key, String value) {
        return put(key, LDValue.of(value));
    }

    /**
     * Returns an object containing the builder's current elements. Subsequent changes to the
     * builder will not affect this value (it uses copy-on-write logic, so the previous values will
     * only be copied to a new map if you continue to add elements after calling {@link #build()}.
     *
     * @return an {@link LDValue} that is a JSON object
     */
    public LDValue build() {
        copyOnWrite = true;
        return LDValueObject.fromMap(builder);
    }
}
