package com.launchdarkly.android.value;

import java.util.ArrayList;

/**
 * A builder created by {@link LDValue#buildArray()}. Builder methods are not thread-safe.
 *
 * @since 4.8.0
 */
public final class ArrayBuilder {
    private volatile ArrayList<LDValue> builder = new ArrayList<>();
    private volatile boolean copyOnWrite = false;

    /**
     * Adds a new element to the builder.
     *
     * @param value the new element
     * @return the same builder
     */
    public ArrayBuilder add(LDValue value) {
        if (copyOnWrite) {
            builder = new ArrayList<>(builder);
            copyOnWrite = false;
        }
        builder.add(value);
        return this;
    }

    /**
     * Adds a new element to the builder.
     *
     * @param value the new element
     * @return the same builder
     */
    public ArrayBuilder add(boolean value) {
        return add(LDValue.of(value));
    }

    /**
     * Adds a new element to the builder.
     *
     * @param value the new element
     * @return the same builder
     */
    public ArrayBuilder add(int value) {
        return add(LDValue.of(value));
    }

    /**
     * Adds a new element to the builder.
     *
     * @param value the new element
     * @return the same builder
     */
    public ArrayBuilder add(long value) {
        return add(LDValue.of(value));
    }

    /**
     * Adds a new element to the builder.
     *
     * @param value the new element
     * @return the same builder
     */
    public ArrayBuilder add(float value) {
        return add(LDValue.of(value));
    }

    /**
     * Adds a new element to the builder.
     *
     * @param value the new element
     * @return the same builder
     */
    public ArrayBuilder add(double value) {
        return add(LDValue.of(value));
    }

    /**
     * Adds a new element to the builder.
     *
     * @param value the new element
     * @return the same builder
     */
    public ArrayBuilder add(String value) {
        return add(LDValue.of(value));
    }

    /**
     * Returns an array containing the builder's current elements. Subsequent changes to the builder
     * will not affect this value (it uses copy-on-write logic, so the previous values will only be
     * copied to a new map if you continue to add elements after calling {@link #build()}.
     *
     * @return an {@link LDValue} that is an array
     */
    public LDValue build() {
        copyOnWrite = true;
        return LDValueArray.fromList(builder);
    }
}
