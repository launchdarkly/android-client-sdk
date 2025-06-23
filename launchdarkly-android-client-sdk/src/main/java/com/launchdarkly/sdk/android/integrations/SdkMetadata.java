package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;

/**
 * Metadata about the LaunchDarkly SDK.
 */
public final class SdkMetadata {

    @NonNull
    private final String name;
    @NonNull
    private final String version;

    public SdkMetadata(@NonNull String name, @NonNull String version) {
        this.name = name;
        this.version = version;
    }

    /**
     * @return name of the SDK for informational purposes such as logging
     */
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * @return version of the SDK for informational purposes such as logging
     */
    @NonNull
    public String getVersion() {
        return version;
    }
}
