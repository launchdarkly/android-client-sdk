package com.launchdarkly.android;


import com.google.gson.JsonElement;
import com.launchdarkly.android.LDUser;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

public interface LDClientInterface extends Closeable {
    boolean initialized();

    void track(String eventName, JsonElement data);

    void track(String eventName);


    void identify(LDUser user);

    Map<String, ?> allFlags();

    Boolean boolVariation(String featureKey, boolean defaultValue);

//    @Deprecated
//    boolean toggle(String featureKey, LDUser user, boolean defaultValue);

    Integer intVariation(String featureKey, int defaultValue);

    Float floatVariation(String featureKey, Float defaultValue);

    String stringVariation(String featureKey, String defaultValue);

//    JsonElement jsonVariation(String featureKey, JsonElement defaultValue);

    JsonElement jsonVariation(String featureKey, JsonElement defaultValue);

    @Override
    void close() throws IOException;

    void flush();

    boolean isOffline();

//    String secureModeHash(LDUser user);
}
