package com.launchdarkly.android;


import com.google.gson.JsonElement;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Future;

public interface LDClientInterface extends Closeable {
    boolean initialized();

    void track(String eventName, JsonElement data);

    void track(String eventName);

    Future<Void> identify(LDUser user);

    Map<String, ?> allFlags();

    Boolean boolVariation(String featureKey, boolean defaultValue);

    Integer intVariation(String featureKey, int defaultValue);

    Float floatVariation(String featureKey, Float defaultValue);

    String stringVariation(String featureKey, String defaultValue);

    JsonElement jsonVariation(String featureKey, JsonElement defaultValue);

    @Override
    void close() throws IOException;

    void flush();

    boolean isOffline();
}
