package com.launchdarkly.sdk.android.integrations;

// TODO: figure out usage of sealed class here
public class PluginMetadata {

    private final String name;

    public PluginMetadata(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
