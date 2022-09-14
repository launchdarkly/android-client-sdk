package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.google.gson.reflect.TypeToken;
import com.launchdarkly.sdk.internal.GsonHelpers;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * An immutable set of flag data.
 */
final class EnvironmentData {
    static final Type FLAGS_MAP_TYPE =
            new TypeToken<Map<String, Flag>>() {}.getType();

    @NonNull
    private final Map<String, Flag> flags;

    public EnvironmentData() {
        this(new HashMap<>());
    }

    public EnvironmentData(Map<String, Flag> flags) {
        this.flags = flags == null ? new HashMap<>() : new HashMap<>(flags);
    }

    public Flag getFlag(String key) {
        return flags.get(key);
    }

    public Map<String, Flag> getAll() {
        return new HashMap<>(flags);
    }

    public Collection<Flag> values() {
        return flags.values();
    }

    public EnvironmentData withFlagUpdatedOrAdded(Flag flag) {
        if (flag == null) {
            return this;
        }
        Map<String, Flag> newFlags = new HashMap<>(flags);
        newFlags.put(flag.getKey(), flag);
        return new EnvironmentData(newFlags);
    }

    public EnvironmentData withFlagRemoved(String key) {
        if (key == null || !flags.containsKey(key)) {
            return this;
        }
        Map<String, Flag> newFlags = new HashMap<>(flags);
        newFlags.remove(key);
        return new EnvironmentData(newFlags);
    }

    public static EnvironmentData fromJson(String json) {
        return new EnvironmentData(
                GsonHelpers.gsonInstance().fromJson(json, FLAGS_MAP_TYPE));
    }

    public String toJson() {
        return GsonHelpers.gsonInstance().toJson(flags);
    }
}
