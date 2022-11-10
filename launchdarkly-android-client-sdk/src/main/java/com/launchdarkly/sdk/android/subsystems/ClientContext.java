package com.launchdarkly.sdk.android.subsystems;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.LDClient;
import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;

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
    private final LDLogger baseLogger;
    private final LDConfig config;
    private final boolean evaluationReasons;
    private final String environmentName;
    private final LDContext initialEvaluationContext;
    private final boolean initiallyInBackground;
    private final boolean initiallySetOffline;
    private final String mobileKey;
    private final ServiceEndpoints serviceEndpoints;
    private final boolean useReport;

    public ClientContext(
            String mobileKey,
            LDLogger baseLogger,
            LDConfig config,
            String environmentName,
            boolean evaluationReasons,
            LDContext initialEvaluationContext,
            boolean initiallyInBackground,
            boolean initiallySetOffline,
            ServiceEndpoints serviceEndpoints,
            boolean useReport
    ) {
        this.mobileKey = mobileKey;
        this.baseLogger = baseLogger;
        this.config = config;
        this.environmentName = environmentName;
        this.evaluationReasons = evaluationReasons;
        this.initialEvaluationContext = initialEvaluationContext;
        this.initiallyInBackground = initiallyInBackground;
        this.initiallySetOffline = initiallySetOffline;
        this.serviceEndpoints = serviceEndpoints;
        this.useReport = useReport;
    }

    protected ClientContext(ClientContext copyFrom) {
        this(
                copyFrom.mobileKey,
                copyFrom.baseLogger,
                copyFrom.config,
                copyFrom.environmentName,
                copyFrom.evaluationReasons,
                copyFrom.initialEvaluationContext,
                copyFrom.initiallyInBackground,
                copyFrom.initiallySetOffline,
                copyFrom.serviceEndpoints,
                copyFrom.useReport
        );
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
     * Returns the evaluation context that the SDK was initialized with.
     * @return the initial evaluation context
     */
    public LDContext getInitialEvaluationContext() {
        return initialEvaluationContext;
    }

    /**
     * Returns true if the application was in the background at initialization time.
     * @return true if initially in the background
     */
    public boolean isInitiallyInBackground() {
        return initiallyInBackground;
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

    /**
     * Returns true if the SDK is configured to use HTTP REPORT mode.
     * @return true if report mode is enabled
     */
    public boolean isUseReport() {
        return useReport;
    }
}
