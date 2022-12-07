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
 * client, as well as its current state. This includes properties from {@link LDConfig}, and also
 * values that are computed during initialization. It is preferable for components to copy
 * properties from this class rather than to retain a reference to the entire {@link LDConfig} object.
 * <p>
 * The actual implementation class may contain other properties that are only relevant to the built-in
 * SDK components and are therefore not part of this base class; this allows the SDK to add its own
 * context information as needed without disturbing the public API.
 *
 * @since 3.3.0
 */
public class ClientContext {
    private final LDLogger baseLogger;
    private final LDConfig config;
    private final DataSourceUpdateSink dataSourceUpdateSink;
    private final boolean evaluationReasons;
    private final String environmentName;
    private final LDContext evaluationContext;
    private final HttpConfiguration http;
    private final boolean inBackground;
    private final String mobileKey;
    private final ServiceEndpoints serviceEndpoints;
    private final boolean setOffline;

    public ClientContext(
            String mobileKey,
            LDLogger baseLogger,
            LDConfig config,
            DataSourceUpdateSink dataSourceUpdateSink,
            String environmentName,
            boolean evaluationReasons,
            LDContext evaluationContext,
            HttpConfiguration http,
            boolean inBackground,
            ServiceEndpoints serviceEndpoints,
            boolean setOffline
    ) {
        this.mobileKey = mobileKey;
        this.baseLogger = baseLogger;
        this.config = config;
        this.dataSourceUpdateSink = dataSourceUpdateSink;
        this.environmentName = environmentName;
        this.evaluationReasons = evaluationReasons;
        this.evaluationContext = evaluationContext;
        this.http = http;
        this.inBackground = inBackground;
        this.serviceEndpoints = serviceEndpoints;
        this.setOffline = setOffline;
    }

    protected ClientContext(ClientContext copyFrom) {
        this(
                copyFrom.mobileKey,
                copyFrom.baseLogger,
                copyFrom.config,
                copyFrom.dataSourceUpdateSink,
                copyFrom.environmentName,
                copyFrom.evaluationReasons,
                copyFrom.evaluationContext,
                copyFrom.http,
                copyFrom.inBackground,
                copyFrom.serviceEndpoints,
                copyFrom.setOffline
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

    public DataSourceUpdateSink getDataSourceUpdateSink() {
        return dataSourceUpdateSink;
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
     * Returns the current evaluation context as of the time that this component was created.
     * @return the current evaluation context
     */
    public LDContext getEvaluationContext() {
        return evaluationContext;
    }

    /**
     * Returns true if the application was in the background at the time that this component was
     * created.
     * @return true if in the background
     */
    public boolean isInBackground() {
        return inBackground;
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
     * Returns true if the application has specified that the SDK should be offline.
     * @return true if set to be offline
     */
    public boolean isSetOffline() {
        return setOffline;
    }
}
