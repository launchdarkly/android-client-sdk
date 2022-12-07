package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.google.gson.reflect.TypeToken;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.internal.GsonHelpers;
import com.launchdarkly.sdk.json.SerializationException;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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

    private EnvironmentData(Map<String, Flag> flags) {
        this.flags = flags == null ? new HashMap<>() : flags;
    }

    public static EnvironmentData copyingFlagsMap(Map<String, Flag> flags) {
        return new EnvironmentData(flags == null ? null : new HashMap<>(flags));
    }

    public static EnvironmentData usingExistingFlagsMap(Map<String, Flag> flags) {
        return new EnvironmentData(flags);
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

    public static EnvironmentData fromJson(String json) throws SerializationException {
        Map<String, Flag> dataMap;
        try {
            dataMap = GsonHelpers.gsonInstance().fromJson(json, FLAGS_MAP_TYPE);
        } catch (Exception e) { // Gson throws various kinds of parsing exceptions that have no common base class
            throw new SerializationException(e);
        }
        // Normalize the data set to ensure that the flag keys are present not only as map keys,
        // but also in each Flag object. That is normally the case in data sent by LD, even though
        // it's redundant, but if for any reason it isn't we can transparently fix it.
        for (Map.Entry<String, Flag> e: dataMap.entrySet()) {
            Flag f = e.getValue();
            if (f.getKey() == null) {
                f = new Flag(e.getKey(), f.getValue(), f.getVersion(), f.getFlagVersion(),
                        f.getVariation(), f.isTrackEvents(), f.isTrackReason(), f.getDebugEventsUntilDate(),
                        f.getReason());
                dataMap.put(e.getKey(), f);
            }
        }
        return new EnvironmentData(dataMap);
    }

    public String toJson() {
        return GsonHelpers.gsonInstance().toJson(flags);
    }
}
