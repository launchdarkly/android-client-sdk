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
    private final ApplicationInfo applicationInfo;
    private final LDLogger baseLogger;
    private final LDConfig config;
    private final DataSourceUpdateSink dataSourceUpdateSink;
    private final boolean evaluationReasons;
    private final String environmentName;
    private final LDContext evaluationContext;
    private final HttpConfiguration http;
    private final boolean inBackground;
    private final String mobileKey;
    private final Boolean previouslyInBackground;
    private final ServiceEndpoints serviceEndpoints;
    private final boolean setOffline;

    /**
     * Constructs an instance, specifying all properties.
     *
     * @param mobileKey see {@link #getMobileKey()}
     * @param baseLogger see {@link #getBaseLogger()}
     * @param config see {@link #getConfig()}
     * @param dataSourceUpdateSink see {@link #getDataSourceUpdateSink()}
     * @param environmentName see {@link #getEnvironmentName()}
     * @param evaluationReasons see {@link #isEvaluationReasons()}
     * @param evaluationContext see {@link #getEvaluationContext()}
     * @param http see {@link #getHttp()}
     * @param inBackground see {@link #isInBackground()}
     * @param previouslyInBackground see {@link #getPreviouslyInBackground()}
     * @param serviceEndpoints see {@link #getServiceEndpoints()}
     * @param setOffline see {@link #isSetOffline()}
     */
    public ClientContext(
            String mobileKey,
            ApplicationInfo applicationInfo,
            LDLogger baseLogger,
            LDConfig config,
            DataSourceUpdateSink dataSourceUpdateSink,
            String environmentName,
            boolean evaluationReasons,
            LDContext evaluationContext,
            HttpConfiguration http,
            boolean inBackground,
            Boolean previouslyInBackground,
            ServiceEndpoints serviceEndpoints,
            boolean setOffline
    ) {
        this.mobileKey = mobileKey;
        this.applicationInfo = applicationInfo;
        this.baseLogger = baseLogger;
        this.config = config;
        this.dataSourceUpdateSink = dataSourceUpdateSink;
        this.environmentName = environmentName;
        this.evaluationReasons = evaluationReasons;
        this.evaluationContext = evaluationContext;
        this.http = http;
        this.inBackground = inBackground;
        this.previouslyInBackground = previouslyInBackground;
        this.serviceEndpoints = serviceEndpoints;
        this.setOffline = setOffline;
    }

    /**
     * Deprecated constructor overload.
     *
     * @param mobileKey see {@link #getMobileKey()}
     * @param baseLogger see {@link #getBaseLogger()}
     * @param config see {@link #getConfig()}
     * @param dataSourceUpdateSink see {@link #getDataSourceUpdateSink()}
     * @param environmentName see {@link #getEnvironmentName()}
     * @param evaluationReasons see {@link #isEvaluationReasons()}
     * @param evaluationContext see {@link #getEvaluationContext()}
     * @param http see {@link #getHttp()}
     * @param inBackground see {@link #isInBackground()}
     * @param serviceEndpoints see {@link #getServiceEndpoints()}
     * @param setOffline see {@link #isSetOffline()}
     * @deprecated use newer constructor
     */
    @Deprecated
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
            Boolean previouslyInBackground,
            ServiceEndpoints serviceEndpoints,
            boolean setOffline
    ) {
        this(mobileKey, null, baseLogger, config, dataSourceUpdateSink, environmentName,
                evaluationReasons, evaluationContext, http, inBackground, previouslyInBackground,
                serviceEndpoints, setOffline);
    }

    /**
     * Deprecated constructor overload.
     *
     * @param mobileKey see {@link #getMobileKey()}
     * @param baseLogger see {@link #getBaseLogger()}
     * @param config see {@link #getConfig()}
     * @param dataSourceUpdateSink see {@link #getDataSourceUpdateSink()}
     * @param environmentName see {@link #getEnvironmentName()}
     * @param evaluationReasons see {@link #isEvaluationReasons()}
     * @param evaluationContext see {@link #getEvaluationContext()}
     * @param http see {@link #getHttp()}
     * @param inBackground see {@link #isInBackground()}
     * @param serviceEndpoints see {@link #getServiceEndpoints()}
     * @param setOffline see {@link #isSetOffline()}
     * @deprecated use newer constructor
     */
    @Deprecated
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
        this(mobileKey, null, baseLogger, config, dataSourceUpdateSink, environmentName,
                evaluationReasons, evaluationContext, http, inBackground,
                null,
                serviceEndpoints, setOffline);
    }

    protected ClientContext(ClientContext copyFrom) {
        this(
                copyFrom.mobileKey,
                copyFrom.applicationInfo,
                copyFrom.baseLogger,
                copyFrom.config,
                copyFrom.dataSourceUpdateSink,
                copyFrom.environmentName,
                copyFrom.evaluationReasons,
                copyFrom.evaluationContext,
                copyFrom.http,
                copyFrom.inBackground,
                copyFrom.previouslyInBackground,
                copyFrom.serviceEndpoints,
                copyFrom.setOffline
        );
    }

    /**
     * The application metadata object.
     * @return the application metadata
     */
    public ApplicationInfo getApplicationInfo() {
        return applicationInfo;
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
     * Returns the previous background state.
     * <p>
     * This is initially null when the SDK is initialized. It is set to {@code true} or
     * {@code false} when the SDK is restarting the data source due to a state change, in case the
     * data source might need to have different behavior depending on whether the state change
     * included a change in foreground/background state.
     *
     * @return {@code true} if the application was in the background before the time that this
     *   component was created; {@code false} if it was in the foreground; or {@code null} if this
     *   is the first time the component is being created during the lifetime of the SDK
     */
    public Boolean getPreviouslyInBackground() {
        return previouslyInBackground;
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
