package com.launchdarkly.android;


import com.google.gson.JsonElement;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.Future;

public interface LDClientInterface extends Closeable {
    void track(String eventName, JsonElement data);

    void track(String eventName);

    Future<Void> identify(LDUser user);

    Map<String, ?> allFlags();

    Boolean boolVariation(String flagKey, boolean defaultValue);

    Integer intVariation(String flagKey, int defaultValue);

    Float floatVariation(String flagKey, Float defaultValue);

    String stringVariation(String flagKey, String defaultValue);

    JsonElement jsonVariation(String flagKey, JsonElement defaultValue);

    void flush();

    boolean isOffline();

    void registerFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener);

    void unregisterFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener);
}
