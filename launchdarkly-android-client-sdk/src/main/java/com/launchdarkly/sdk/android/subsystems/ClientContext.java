package com.launchdarkly.sdk.android.subsystems;

import android.app.Application;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.LDClient;
import com.launchdarkly.sdk.android.LDConfig;

/**
 * Configuration information provided by the {@link com.launchdarkly.sdk.android.LDClient} when
 * creating components.
 * <p>
 * The getter methods in this class provide information about the initial configuration of the
 * client. This includes properties from {@link LDConfig}, and also values that are computed
 * during initialization. It is preferable for components to copy properties from this class rather
 * than to retain a reference to the entire {@link LDConfig} object.
 * <p>
 * The actual implementation class may contain other properties that are only relevant to the built-in
 * SDK components and are therefore not part of this base class; this allows the SDK to add its own
 * context information as needed without disturbing the public API.
 * <p>
 * All properties of this object are immutable; they are set at initialization time and do not
 * reflect any later state changes in the client.
 *
 * @since 3.3.0
 */
public class ClientContext {
    private final Application application;
    private final LDLogger baseLogger;
    private final LDConfig config;
    private final boolean evaluationReasons;
    private final String environmentName;
    private final HttpConfiguration http;
    private final boolean initiallySetOffline;
    private final String mobileKey;
    private final ServiceEndpoints serviceEndpoints;

    public ClientContext(
            Application application,
            String mobileKey,
            LDLogger baseLogger,
            LDConfig config,
            String environmentName,
            boolean evaluationReasons,
            HttpConfiguration http,
            boolean initiallySetOffline,
            ServiceEndpoints serviceEndpoints
    ) {
        this.application = application;
        this.mobileKey = mobileKey;
        this.baseLogger = baseLogger;
        this.config = config;
        this.environmentName = environmentName;
        this.evaluationReasons = evaluationReasons;
        this.http = http;
        this.initiallySetOffline = initiallySetOffline;
        this.serviceEndpoints = serviceEndpoints;
    }

    protected ClientContext(ClientContext copyFrom) {
        this(
                copyFrom.application,
                copyFrom.mobileKey,
                copyFrom.baseLogger,
                copyFrom.config,
                copyFrom.environmentName,
                copyFrom.evaluationReasons,
                copyFrom.http,
                copyFrom.initiallySetOffline,
                copyFrom.serviceEndpoints
        );
    }

    /**
     * The Android application object.
     * @return the application
     */
    public Application getApplication() {
        return application;
    }

    /**
     * The base logger for the SDK.
     * @return a logger instance
     */
    public LDLogger getBaseLogger() {
        return baseLogger;
    }

    /**
     * Returns the full configuration object. THIS IS A TEMPORARY METHOD that will be removed prior
     * to release-- the goal is to NOT retain the full LDConfig in these components, but until we
     * have moved more of the config properties into subconfiguration builders, this is necessary.
     * @return the configuration object
     */
    public LDConfig getConfig() {
        return config;
    }

    /**
     * Returns the configured environment name.
     * @return the environment name
     */
    public String getEnvironmentName() {
        return environmentName;
    }

    /**
     * Returns true if evaluation reasons are enabled.
     * @return true if evaluation reasons are enabled
     */
    public boolean isEvaluationReasons() {
        return evaluationReasons;
    }

    /**
     * Returns the HTTP configuration.
     * @return the HTTP configuration
     */
    public HttpConfiguration getHttp() {
        return http;
    }

    /**
     * Returns true if the initial configuration specified that the SDK should be offline.
     * @return true if initially set to be offline
     */
    public boolean isInitiallySetOffline() {
        return initiallySetOffline;
    }

    /**
     * Returns the configured mobile key.
     * <p>
     * In multi-environment mode, there is a separate {@link ClientContext} for each environment,
     * corresponding to the {@link LDClient} instance for that environment.
     *
     * @return the mobile key
     */
    public String getMobileKey() {
        return mobileKey;
    }

    /**
     * Returns the base service URIs used by SDK components.
     * @return the service endpoint URIs
     */
    public ServiceEndpoints getServiceEndpoints() {
        return serviceEndpoints;
    }
}