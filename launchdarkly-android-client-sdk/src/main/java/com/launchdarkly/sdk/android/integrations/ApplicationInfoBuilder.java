package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

/**
 * Contains methods for configuring the SDK's application metadata.
 * <p>
 * Application metadata may be used in LaunchDarkly analytics or other product features, but does not affect feature flag evaluations.
 * <p>
 * If you want to set non-default values for any of these fields, create a builder with
 * {@link Components#applicationInfo()}, change its properties with the methods of this class,
 * and pass it to {@link com.launchdarkly.sdk.android.LDConfig.Builder#applicationInfo(ApplicationInfoBuilder)}:
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder()
 *         .applicationInfo(
 *             Components.applicationInfo()
 *                 .applicationId("authentication-service")
 *                 .applicationVersion("1.0.0")
 *         )
 *         .build();
 * </code></pre>
 * <p>
 *
 * @since 4.1.0
 */
public final class ApplicationInfoBuilder {
    private String applicationId;
    private String applicationName;
    private String applicationVersion;
    private String applicationVersionName;

    /**
     * Create an empty ApplicationInfoBuilder.
     *
     * @see Components#applicationInfo()
     */
    public ApplicationInfoBuilder() {}

    /**
     * Sets a unique identifier representing the application where the LaunchDarkly SDK is running.
     * <p>
     * This can be specified as any string value as long as it only uses the following characters: ASCII
     * letters, ASCII digits, period, hyphen, underscore. A string containing any other characters will be
     * ignored.
     *
     * @param applicationId the application identifier
     * @return the builder
     */
    public ApplicationInfoBuilder applicationId(String applicationId) {
        this.applicationId = applicationId;
        return this;
    }

    /**
     * Sets a user friendly name for the application in which the LaunchDarkly SDK is running.
     * <p>
     * This can be specified as any string value as long as it only uses the following characters: ASCII
     * letters, ASCII digits, period, hyphen, underscore. A string containing any other characters will be
     * ignored.
     *
     * @param applicationName the user friendly name
     * @return the builder
     */
    public ApplicationInfoBuilder applicationName(String applicationName) {
        this.applicationName = applicationName;
        return this;
    }

    /**
     * Sets a unique identifier representing the version of the application where the LaunchDarkly SDK
     * is running.
     * <p>
     * This can be specified as any string value as long as it only uses the following characters: ASCII
     * letters, ASCII digits, period, hyphen, underscore. A string containing any other characters will be
     * ignored.
     *
     * @param version the application version
     * @return the builder
     */
    public ApplicationInfoBuilder applicationVersion(String version) {
        this.applicationVersion = version;
        return this;
    }

    /**
     * Sets a user friendly name for the version of the application in which the LaunchDarkly SDK is running.
     * <p>
     * This can be specified as any string value as long as it only uses the following characters: ASCII
     * letters, ASCII digits, period, hyphen, underscore. A string containing any other characters will be
     * ignored.
     *
     * @param versionName the user friendly version name
     * @return the builder
     */
    public ApplicationInfoBuilder applicationVersionName(String versionName) {
        this.applicationVersionName = versionName;
        return this;
    }

    /**
     * Called internally by the SDK to create the configuration object.
     *
     * @return the configuration object
     */
    public ApplicationInfo createApplicationInfo() {
        return new ApplicationInfo(applicationId, applicationVersion, applicationName, applicationVersionName);
    }
}
