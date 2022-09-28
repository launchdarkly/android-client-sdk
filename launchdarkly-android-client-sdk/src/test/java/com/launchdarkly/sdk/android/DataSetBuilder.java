package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.LDValue;

import java.util.HashMap;
import java.util.Map;

public class DataSetBuilder {
    private final Map<String, Flag> flags = new HashMap<>();

    public EnvironmentData build() {
        return new EnvironmentData(flags);
    }

    public DataSetBuilder add(Flag flag) {
        flags.put(flag.getKey(), flag);
        return this;
    }

    public DataSetBuilder add(String flagKey, int version, LDValue value, int variation) {
        return add(new Flag(flagKey, value, version, null, variation,
                null, null, null, null));
    }
}
