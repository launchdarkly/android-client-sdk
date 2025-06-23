package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

/**
 * Metadata about the environment that flag evaluations or other functionalities are being performed in.
 */
public final class EnvironmentMetadata {
    private final ApplicationInfo applicationInfo;
    private final SdkMetadata sdkMetadata;
    private final String credential;

    /**
     * @param applicationInfo for the application this SDK is used in
     * @param sdkMetadata for the LaunchDarkly SDK
     * @param credential for authentication to LaunchDarkly endpoints for this environment
     */
    public EnvironmentMetadata(ApplicationInfo applicationInfo, SdkMetadata sdkMetadata, String credential) {
        this.applicationInfo = applicationInfo;
        this.sdkMetadata = sdkMetadata;
        this.credential = credential;
    }

    /**
     * @return the {@link ApplicationInfo} for the application this SDK is used in.
     */
    public ApplicationInfo getApplicationInfo() {
        return applicationInfo;
    }

    /**
     * @return the {@link SdkMetadata} for the LaunchDarkly SDK.
     */
    public SdkMetadata getSdkMetadata() {
        return sdkMetadata;
    }

    /**
     * @return the credential for authentication to LaunchDarkly endpoints for this environment
     */
    public String getCredential() {
        return credential;
    }
}
