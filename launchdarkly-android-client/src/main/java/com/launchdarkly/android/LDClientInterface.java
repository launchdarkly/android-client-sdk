package com.launchdarkly.android;


import com.google.gson.JsonElement;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.Future;

public interface LDClientInterface extends Closeable {
    boolean isInitialized();

    boolean isOffline();

    void setOffline();

    void setOnline();

    boolean isConnection401Error();

    void track(String eventName, JsonElement data);

    void track(String eventName);

    Future<Void> identify(LDUser user);

    void flush();

    Map<String, ?> allFlags();

    Boolean boolVariation(String flagKey, Boolean fallback);

    Integer intVariation(String flagKey, Integer fallback);

    Float floatVariation(String flagKey, Float fallback);

    String stringVariation(String flagKey, String fallback);

    JsonElement jsonVariation(String flagKey, JsonElement fallback);

    void registerFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener);

    void unregisterFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener);

    boolean isDisableBackgroundPolling();
}
