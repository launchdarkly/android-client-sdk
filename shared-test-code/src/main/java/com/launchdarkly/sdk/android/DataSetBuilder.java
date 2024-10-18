package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;

import java.util.HashMap;
import java.util.Map;

public final class DataSetBuilder {
    private final Map<String, Flag> flags = new HashMap<>();

    public static EnvironmentData emptyData() {
        return new DataSetBuilder().build();
    }

    public EnvironmentData build() {
        return EnvironmentData.copyingFlagsMap(flags);
    }

    public DataSetBuilder add(Flag flag) {
        flags.put(flag.getKey(), flag);
        return this;
    }

    public DataSetBuilder add(String flagKey, int version, LDValue value, int variation) {
        return add(new Flag(flagKey, value, version, null, variation,
                false, false, null, null, null));
    }

    public DataSetBuilder add(String flagKey, LDValue value, int variation) {
        return add(flagKey, 1, value, variation);
    }
}
