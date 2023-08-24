package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Consumer;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.LDAndroidLogging;
import com.launchdarkly.sdk.android.LDPackageConsts;
import com.launchdarkly.sdk.android.LDUtil;
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
    @Nullable
    private String applicationId;
    @Nullable
    private String applicationName;
    @Nullable
    private String applicationVersion;
    @Nullable
    private String applicationVersionName;

    @VisibleForTesting
    LDLogger logger = LDLogger.withAdapter(LDAndroidLogging.adapter(), ApplicationInfoBuilder.class.getName());

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
        validatedThenChange(this.logger, s -> this.applicationId = s, applicationId);
        return this;
    }

    /**
     * Sets a human friendly name for the application in which the LaunchDarkly SDK is running.
     * <p>
     * This can be specified as any string value as long as it only uses the following characters: ASCII
     * letters, ASCII digits, period, hyphen, underscore. A string containing any other characters will be
     * ignored.
     *
     * @param applicationName the human friendly name
     * @return the builder
     */
    public ApplicationInfoBuilder applicationName(String applicationName) {
        validatedThenChange(this.logger, s -> this.applicationName = s, applicationName);
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
        validatedThenChange(this.logger, s -> this.applicationVersion = s, version);
        return this;
    }

    /**
     * Sets a human friendly name for the version of the application in which the LaunchDarkly SDK is running.
     * <p>
     * This can be specified as any string value as long as it only uses the following characters: ASCII
     * letters, ASCII digits, period, hyphen, underscore. A string containing any other characters will be
     * ignored.
     *
     * @param versionName the human friendly version name
     * @return the builder
     */
    public ApplicationInfoBuilder applicationVersionName(String versionName) {
        validatedThenChange(this.logger, s -> this.applicationVersionName = s, versionName);
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

    /**
     * @param propertySetter lambda for setting the property.  Java is fun and has predefined
     *                       functional interfaces.
     * @param input          the string that will be sanitized and validated then applied
     */
    private void validatedThenChange(LDLogger logger, Consumer<String> propertySetter, String input) {
        if (input == null) {
            propertySetter.accept(input);
            return;
        }

        String sanitized = LDUtil.sanitizeSpaces(input);
        String error = LDUtil.validateStringValue(sanitized);
        if (error != null) {
            // TODO: make sure log includes property name
            logger.warn(LDPackageConsts.DEFAULT_LOGGER_NAME, propertySetter.toString() + error);
            return;
        }

        propertySetter.accept(sanitized);
    }
}
