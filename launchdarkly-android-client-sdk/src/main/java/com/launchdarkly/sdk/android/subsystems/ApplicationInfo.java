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
    private final String applicationId;
    private final String applicationName;
    private final String applicationVersion;
    private final String applicationVersionName;

    /**
     * Used internally by the SDK to store application metadata.
     *
     * @param applicationId          the application ID
     * @param applicationVersion     the application version
     * @param applicationName        friendly name for the application
     * @param applicationVersionName friendly name for the version
     * @see ApplicationInfoBuilder
     */
    public ApplicationInfo(String applicationId, String applicationVersion,
                           String applicationName, String applicationVersionName) {
        this.applicationId = applicationId;
        this.applicationVersion = applicationVersion;
        this.applicationName = applicationName;
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
     * A human friendly name for the application in which the LaunchDarkly SDK is running.
     *
     * @return the friendly name of the application, or null
     */
    public String getApplicationName() {
        return applicationName;
    }

    /**
     * A human friendly name for the version of the application in which the LaunchDarkly SDK is running.
     *
     * @return the friendly name of the version, or null
     */
    public String getApplicationVersionName() {
        return applicationVersionName;
    }
}
