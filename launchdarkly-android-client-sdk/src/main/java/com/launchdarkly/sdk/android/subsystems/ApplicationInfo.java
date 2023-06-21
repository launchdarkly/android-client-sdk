package com.launchdarkly.sdk.android.subsystems;

import com.launchdarkly.sdk.android.integrations.ApplicationInfoBuilder;

/**
 * Encapsulates the SDK's application metadata.
 * <p>
 * See {@link ApplicationInfoBuilder} for more details on these properties.
 *
 * @since 4.1.0
 */
public final class ApplicationInfo {
    private String applicationId;
    private String applicationName;
    private String applicationVersion;
    private String applicationVersionName;

    /**
     * Used internally by the SDK to store application metadata.
     *
     * @param applicationId the application ID
     * @param applicationVersion the application version
     * @see ApplicationInfoBuilder
     */
    public ApplicationInfo(String applicationId, String applicationName,
                           String applicationVersion, String applicationVersionName) {
        this.applicationId = applicationId;
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
        this.applicationVersionName = applicationVersionName;
    }

    /**
     * A unique identifier representing the application where the LaunchDarkly SDK is running.
     *
     * @return the application identifier, or null
     */
    public String getApplicationId() {
        return applicationId;
    }

    /**
     * A unique identifier representing the version of the application where the
     * LaunchDarkly SDK is running.
     *
     * @return the application version, or null
     */
    public String getApplicationVersion() {
        return applicationVersion;
    }

    /**
     * A user friendly name for the application in which the LaunchDarkly SDK is running.
     *
     * @return the friendly name of the application, or null
     */
    public String getApplicationName() {
        return applicationName;
    }

    /**
     * A user friendly name for the version of the application in which the LaunchDarkly SDK is running.
     *
     * @return the friendly name of the version, or null
     */
    public String getApplicationVersionName() {
        return applicationVersionName;
    }
}
