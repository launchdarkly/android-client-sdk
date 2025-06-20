package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class SdkMetadata {

    @NonNull
    private final String name;
    @NonNull
    private final String version;
    @Nullable
    private final String wrapperName;
    @Nullable
    private final String wrapperVersion;

    public SdkMetadata(String name, String version) {
        this.name = name;
        this.version = version;
        // TODO: can these be removed?
        this.wrapperName = "";
        this.wrapperVersion = "";
    }

    @NonNull
    public String getName() {
        return name;
    }

    @NonNull
    public String getVersion() {
        return version;
    }

    @Nullable
    public String getWrapperName() {
        return wrapperName;
    }

    @Nullable
    public String getWrapperVersion() {
        return wrapperVersion;
    }
}
