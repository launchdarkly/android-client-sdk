package com.launchdarkly.sdk.android;

public class ConfigHelper {
    // This method exists because Config.Builder.persistentDataStore is currently package-private -
    // using a custom persistence component is not yet part of the public API.
    public static LDConfig.Builder configureIsolatedInMemoryPersistence(LDConfig.Builder builder) {
        builder.persistentDataStore(new InMemoryPersistentDataStore());
        return builder;
    }
}
