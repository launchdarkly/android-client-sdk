package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

// TODO: figure out usage of sealed class here
public final class EnvironmentMetadata {
    private final ApplicationInfo applicationInfo;
    private final SdkMetadata sdkMetadata;
    private final String credential;

    public EnvironmentMetadata(ApplicationInfo applicationInfo, SdkMetadata sdkMetadata, String credential) {
        this.applicationInfo = applicationInfo;
        this.sdkMetadata = sdkMetadata;
        this.credential = credential;
    }

    public ApplicationInfo getApplicationInfo() {
        return applicationInfo;
    }

    public SdkMetadata getSdkMetadata() {
        return sdkMetadata;
    }

    public String getCredential() {
        return credential;
    }
}
