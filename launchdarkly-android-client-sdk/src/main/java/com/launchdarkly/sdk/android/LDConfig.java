package com.launchdarkly.sdk.android;

import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.integrations.ApplicationInfoBuilder;
import com.launchdarkly.sdk.android.integrations.HooksConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.PluginsConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.android.subsystems.HookConfiguration;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;
import com.launchdarkly.sdk.android.subsystems.PluginsConfiguration;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import okhttp3.MediaType;

/**
 * This class exposes advanced configuration options for {@link LDClient}. Instances of this class
 * must be constructed with {@link LDConfig.Builder}.
 */
public class LDConfig {
    /**
     * The default value for {@link com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder#backgroundPollIntervalMillis(int)}
     * and {@link com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder#backgroundPollIntervalMillis(int)}:
     * one hour.
     */
    public static final int DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS = 3_600_000;

    /**
     * The minimum value for {@link com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder#backgroundPollIntervalMillis(int)}
     * and {@link com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder#backgroundPollIntervalMillis(int)}:
     * 15 minutes.
     */
    public static final int MIN_BACKGROUND_POLL_INTERVAL_MILLIS = 900_000;

    static final LDLogLevel DEFAULT_LOG_LEVEL = LDLogLevel.INFO;

    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    static final String primaryEnvironmentName = "default";

    static final int DEFAULT_MAX_CACHED_CONTEXTS = 5;
    static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10_000; // 10 seconds

    private final Map<String, String> mobileKeys;

    final ServiceEndpoints serviceEndpoints;

    @Nullable
    final ApplicationInfo applicationInfo;
    final ComponentConfigurer<DataSource> dataSource;
    final ComponentConfigurer<EventProcessor> events;
    final HookConfiguration hooks;
    final PluginsConfiguration pluginsConfig;
    final ComponentConfigurer<HttpConfiguration> http;

    private final boolean diagnosticOptOut;
    private final boolean disableBackgroundUpdating;
    private final boolean evaluationReasons;
    private final boolean generateAnonymousKeys;
    private final boolean autoEnvAttributes;
    private final LDLogAdapter logAdapter;
    private final String loggerName;
    private final int maxCachedContexts;
    private final boolean offline;
    private final PersistentDataStore persistentDataStore; // configurable for testing only

    LDConfig(Map<String, String> mobileKeys,
             ServiceEndpoints serviceEndpoints,
             ApplicationInfo applicationInfo,
             ComponentConfigurer<DataSource> dataSource,
             ComponentConfigurer<EventProcessor> events,
             HookConfiguration hooks,
             PluginsConfiguration pluginsConfig,
             ComponentConfigurer<HttpConfiguration> http,
             boolean offline,
             boolean disableBackgroundUpdating,
             boolean evaluationReasons,
             boolean diagnosticOptOut,
             int maxCachedContexts,
             boolean generateAnonymousKeys,
             boolean autoEnvAttributes,
             PersistentDataStore persistentDataStore,
             LDLogAdapter logAdapter,
             String loggerName) {
        this.mobileKeys = mobileKeys;
        this.serviceEndpoints = serviceEndpoints;
        this.applicationInfo = applicationInfo;
        this.dataSource = dataSource;
        this.events = events;
        this.hooks = hooks;
        this.pluginsConfig = pluginsConfig;
        this.http = http;
        this.offline = offline;
        this.disableBackgroundUpdating = disableBackgroundUpdating;
        this.evaluationReasons = evaluationReasons;
        this.diagnosticOptOut = diagnosticOptOut;
        this.maxCachedContexts = maxCachedContexts;
        this.generateAnonymousKeys = generateAnonymousKeys;
        this.autoEnvAttributes = autoEnvAttributes;
        this.persistentDataStore = persistentDataStore;
        this.logAdapter = logAdapter;
        this.loggerName = loggerName;
    }

    /**
     * @return the mobile key
     */
    public String getMobileKey() {
        return mobileKeys.get(primaryEnvironmentName);
    }

    /**
     * @return a map of mobile keys keyed by environment name
     */
    public Map<String, String> getMobileKeys() {
        return mobileKeys;
    }

    /**
     * @return true if offline mode is enabled, false otherwise
     */
    public boolean isOffline() {
        return offline;
    }

    /**
     * @return true if background polling is disabled, false otherwise
     */
    public boolean isDisableBackgroundPolling() {
        return disableBackgroundUpdating;
    }

    /**
     * @return true if evaluation reasons are turned on, false otherwise
     */
    public boolean isEvaluationReasons() {
        return evaluationReasons;
    }

    /**
     * @return true if opting out of diagnostics, false otherwise
     */
    boolean getDiagnosticOptOut() {
        return diagnosticOptOut;
    }

    /**
     * @return the maximum number of contexts that will be cached locally
     */
    int getMaxCachedContexts() {
        return maxCachedContexts;
    }

    /**
     * @return true if keys should be generated for anonymous contexts, false otherwise
     */
    public boolean isGenerateAnonymousKeys() { return generateAnonymousKeys; }

    /**
     * @return true if automatic environment attributes should be included in contexts, false otherwise
     */
    public boolean isAutoEnvAttributes() {
        return autoEnvAttributes;
    }

    PersistentDataStore getPersistentDataStore() { return persistentDataStore; }

    LDLogAdapter getLogAdapter() { return logAdapter; }

    String getLoggerName() { return loggerName; }

    /**
     * A <a href="http://en.wikipedia.org/wiki/Builder_pattern">builder</a> that helps construct
     * {@link LDConfig} objects. Builder calls can be chained, enabling the following pattern:
     * <pre>
     * LDConfig config = new LDConfig.Builder()
     *          .mobileKey("mobile-key")
     *          .evaluationReasons(true)
     *          .build();
     * </pre>
     */
    public static class Builder {

        /**
         Enable / disable options for Auto Environment Attributes functionality.  When enabled, the SDK will automatically
         provide data about the mobile environment where the application is running. This data makes it simpler to target
         your mobile customers based on application name or version, or on device characteristics including manufacturer,
         model, operating system, locale, and so on. We recommend enabling this when you configure the SDK.  See TKTK
         for more documentation.
         For example, consider a “dark mode” feature being added to an app. Versions 10 through 14 contain early,
         incomplete versions of the feature. These versions are available to all customers, but the “dark mode” feature is only
         enabled for testers.  With version 15, the feature is considered complete. With Auto Environment Attributes enabled,
         you can use targeting rules to enable "dark mode" for all customers who are using version 15 or greater, and ensure
         that customers on previous versions don't use the earlier, unfinished version of the feature.
         */
        public enum AutoEnvAttributes {
            /**
             * Enables the Auto EnvironmentAttributes functionality.
             */
            Enabled,

            /**
             * Disables the Auto EnvironmentAttributes functionality.
             */
            Disabled
        }

        private String mobileKey;
        private Map<String, String> secondaryMobileKeys;

        private ServiceEndpointsBuilder serviceEndpointsBuilder;

        private ApplicationInfoBuilder applicationInfoBuilder = null;
        private ComponentConfigurer<DataSource> dataSource = null;
        private ComponentConfigurer<EventProcessor> events = null;
        private HooksConfigurationBuilder hooksConfigurationBuilder = null;
        private PluginsConfigurationBuilder pluginsConfigurationBuilder = null;
        private ComponentConfigurer<HttpConfiguration> http = null;

        private int maxCachedContexts = DEFAULT_MAX_CACHED_CONTEXTS;

        private boolean offline = false;
        private boolean disableBackgroundUpdating = false;
        private boolean diagnosticOptOut = false;

        private boolean evaluationReasons = false;

        private boolean generateAnonymousKeys;

        private boolean autoEnvAttributes = false;

        private PersistentDataStore persistentDataStore;

        private LDLogAdapter logAdapter = defaultLogAdapter();
        private String loggerName = LDPackageConsts.DEFAULT_LOGGER_NAME;
        private LDLogLevel logLevel = null;

        /**
         * LDConfig.Builder constructor. Configurable values are all set to their default values. The client app can
         * modify these values as desired.
         *
         * @param autoEnvAttributes - Enable / disable Auto Environment Attributes functionality.  When enabled, the SDK
         *                          will automatically provide data about the mobile environment where the application is
         *                          running. This data makes it simpler to target your mobile customers based on
         *                          application name or version, or on device characteristics including manufacturer,
         *                          model, operating system, locale, and so on. We recommend enabling this when you
         *                          configure the SDK.  See TKTK for more documentation.
         */
        public Builder(AutoEnvAttributes autoEnvAttributes) {
            this.autoEnvAttributes = autoEnvAttributes == AutoEnvAttributes.Enabled; // mapping enum to boolean
        }

        /**
         * Sets the key for authenticating with LaunchDarkly. This is required unless you're using the client in offline mode.
         *
         * @param mobileKey Get this from the LaunchDarkly web app under Team Settings.
         * @return the builder
         */
        public Builder mobileKey(String mobileKey) {
            if (secondaryMobileKeys != null && secondaryMobileKeys.containsValue(mobileKey)) {
                throw new IllegalArgumentException("The primary environment key cannot be in the secondary mobile keys.");
            }

            this.mobileKey = mobileKey;
            return this;
        }

        /**
         * Sets the secondary keys for authenticating to additional LaunchDarkly environments.
         *
         * @param secondaryMobileKeys A map of identifying names to unique mobile keys to access secondary environments
         * @return the builder
         */
        public Builder secondaryMobileKeys(Map<String, String> secondaryMobileKeys) {
            if (secondaryMobileKeys == null) {
                this.secondaryMobileKeys = null;
                return this;
            }

            Map<String, String> unmodifiable = Collections.unmodifiableMap(secondaryMobileKeys);
            if (unmodifiable.containsKey(primaryEnvironmentName)) {
                throw new IllegalArgumentException("The primary environment name is not a valid key.");
            }
            Set<String> secondaryKeys = new HashSet<>(unmodifiable.values());
            if (mobileKey != null && secondaryKeys.contains(mobileKey)) {
                throw new IllegalArgumentException("The primary environment key cannot be in the secondary mobile keys.");
            }
            if (unmodifiable.values().size() != secondaryKeys.size()) {
                throw new IllegalArgumentException("A key can only be used once.");
            }

            this.secondaryMobileKeys = unmodifiable;
            return this;
        }

        /**
         * Sets the base service URIs used by SDK components.
         * <p>
         * This object is a configuration builder obtained from {@link Components#serviceEndpoints()},
         * which has methods for setting each external endpoint to a custom URI.
         * <pre><code>
         *     LDConfig config = new LDConfig.Builder().mobileKey("key")
         *         .serviceEndpoints(
         *             Components.serviceEndpoints().relayProxy("http://my-relay-proxy-host")
         *         );
         * </code></pre>
         *
         * @param serviceEndpointsBuilder a configuration builder object returned by {@link Components#serviceEndpoints()}
         * @return the builder
         * @since 4.0.0
         */
        public Builder serviceEndpoints(ServiceEndpointsBuilder serviceEndpointsBuilder) {
            this.serviceEndpointsBuilder = serviceEndpointsBuilder;
            return this;
        }

        /**
         * Sets the SDK's application metadata, which may be used in LaunchDarkly analytics or other product features,
         * but does not affect feature flag evaluations.
         * <p>
         * This object is normally a configuration builder obtained from {@link Components#applicationInfo()},
         * which has methods for setting individual metadata properties.
         *
         * @param applicationInfoBuilder a configuration builder object returned by {@link Components#applicationInfo()}
         * @return the builder
         * @since 4.1.0
         */
        public Builder applicationInfo(ApplicationInfoBuilder applicationInfoBuilder) {
            this.applicationInfoBuilder = applicationInfoBuilder;
            return this;
        }

        /**
         * Sets the configuration of the component that receives feature flag data from LaunchDarkly.
         * <p>
         * The default is {@link Components#streamingDataSource()}; you may instead use
         * {@link Components#pollingDataSource()}, or a test fixture such as
         * {@link com.launchdarkly.sdk.android.integrations.TestData}. See {@link Components#streamingDataSource()}
         * and {@link Components#pollingDataSource()} for details on how to configure them with
         * options that are specific to streaming or polling mode.
         * <p>
         * Setting {@link LDConfig.Builder#offline(boolean)} to {@code true} will supersede this setting
         * and completely disable all data sources.
         * <pre><code>
         *     // Setting custom options when using streaming mode
         *     LDConfig config = new LDConfig.Builder()
         *         .dataSource(
         *             Components.streamingDataSource()
         *                 .initialReconnectDelayMillis(100)
         *         )
         *         .build();
         *
         *     // Using polling mode instead of streaming, and setting custom options for polling
         *     LDConfig config = new LDConfig.Builder()
         *         .dataSource(
         *             Components.pollingDataSource()
         *                 .pollingIntervalMillis(60_000)
         *         )
         *         .build();
         * </code></pre>
         *
         * @param dataSourceConfigurer the data source configuration builder
         * @return the main configuration builder
         * @see Components#streamingDataSource()
         * @see Components#pollingDataSource()
         * @since 3.3.0
         */
        public Builder dataSource(ComponentConfigurer<DataSource> dataSourceConfigurer) {
            this.dataSource = dataSourceConfigurer;
            return this;
        }

        /**
         * Sets the implementation of {@link EventProcessor} to be used for processing analytics events.
         * <p>
         * The default is {@link Components#sendEvents()} with no custom options. You may instead call
         * {@link Components#sendEvents()} and then set custom options for event processing; or, disable
         * events with {@link Components#noEvents()}; or, choose to use a custom implementation (for
         * instance, a test fixture).
         * <p>
         * Setting {@link LDConfig.Builder#offline(boolean)} to {@code true} will supersede this setting
         * and completely disable network requests.
         * <pre><code>
         *     // Setting custom event processing options
         *     LDConfig config = new LDConfig.Builder()
         *         .events(Components.sendEvents().capacity(100))
         *         .build();
         *
         *     // Disabling events
         *     LDConfig config = new LDConfig.Builder()
         *         .events(Components.noEvents())
         *         .build();
         * </code></pre>
         *
         * @param eventsConfigurer the events configuration builder
         * @return the main configuration builder
         * @since 3.3.0
         * @see Components#sendEvents()
         * @see Components#noEvents()
         */
        public Builder events(ComponentConfigurer<EventProcessor> eventsConfigurer) {
            this.events = eventsConfigurer;
            return this;
        }

        /**
         * Sets the SDK's hooks configuration, using a builder. This is normally a obtained from
         * <p>
         * {@link Components#hooks()} ()}, which has methods for setting individual other hook
         * related properties.
         *
         * @param hooksConfiguration the hooks configuration builder
         * @return the main configuration builder
         * @see Components#hooks()
         */
        public Builder hooks(HooksConfigurationBuilder hooksConfiguration) {
            this.hooksConfigurationBuilder = hooksConfiguration;
            return this;
        }

        /**
         * This is an experimental API and may be removed/changed in the future without notice. It is
         * marked as deprecated as Java does not have built in support for an experimental annotation.
         * 
         * Sets the SDK's plugins configuration, using a builder. This is normally a obtained from
         * <p>
         * {@link Components#plugins()} ()}, which has methods for setting individual plugin
         * related properties.
         *
         * @param pluginsConfiguration the plugins configuration builder
         * @return the main configuration builder
         * @see Components#plugins()
         */
        @Deprecated()
        public Builder plugins(PluginsConfigurationBuilder pluginsConfiguration) {
            this.pluginsConfigurationBuilder = pluginsConfiguration;
            return this;
        }

        /**
         * Sets the SDK's networking configuration, using a configuration builder. This builder is
         * obtained from {@link Components#httpConfiguration()}, and has methods for setting individual
         * HTTP-related properties.
         * <pre><code>
         *     LDConfig config = new LDConfig.Builder()
         *         .http(Components.httpConfiguration().connectTimeoutMillis(5000))
         *         .build();
         * </code></pre>
         *
         * @param httpConfigurer the HTTP configuration builder
         * @return the main configuration builder
         * @since 3.3.0
         * @see Components#httpConfiguration()
         */
        public Builder http(ComponentConfigurer<HttpConfiguration> httpConfigurer) {
            this.http = httpConfigurer;
            return this;
        }

        /**
         * Sets whether feature flag updates should be disabled when your app is in the background.
         * <p>
         * The default value is false (flag updates <i>will</i> be done in the background).
         *
         * @param disableBackgroundUpdating true if the client should skip updating flags when in the background
         * @return the builder
         */
        public Builder disableBackgroundUpdating(boolean disableBackgroundUpdating) {
            this.disableBackgroundUpdating = disableBackgroundUpdating;
            return this;
        }

        /**
         * Disables all network calls from the LaunchDarkly client.
         * <p>
         * This can also be specified after the client has been created, using
         * {@link LDClientInterface#setOffline()}.
         * <p>
         * The default value is true (the client will make network calls).
         *
         * @param offline true if the client should run in offline mode
         * @return the builder
         */
        public Builder offline(boolean offline) {
            this.offline = offline;
            return this;
        }

        /**
         * If enabled, LaunchDarkly will provide additional information about how flag values were
         * calculated. The additional information will then be available through the client's
         * "detail" methods ({@link LDClientInterface#boolVariationDetail(String, boolean)}, etc.).
         *
         * Since this increases the size of network requests, the default is false (detail
         * information will not be sent).
         *
         * @param evaluationReasons  true if detail/reason information should be made available
         * @return the builder
         */
        public Builder evaluationReasons(boolean evaluationReasons) {
            this.evaluationReasons = evaluationReasons;
            return this;
        }

        /**
         * Set to true to opt out of sending diagnostics data.
         *
         * Unless the diagnosticOptOut field is set to true, the client will send some diagnostics data to the
         * LaunchDarkly servers in order to assist in the development of future SDK improvements. These diagnostics
         * consist of an initial payload containing some details of SDK in use, the SDK's configuration, and the platform
         * the SDK is being run on; as well as payloads sent periodically with information on irregular occurrences such
         * as dropped events.
         *
         * @param diagnosticOptOut true if you want to opt out of sending any diagnostics data.
         * @return the builder
         */
        public Builder diagnosticOptOut(boolean diagnosticOptOut) {
            this.diagnosticOptOut = diagnosticOptOut;
            return this;
        }

        /**
         * Sets the maximum number of evaluation contexts to cache the flag values for locally in
         * the device's SharedPreferences.
         * <p>
         * Note that the active evaluation context is not considered part of this limit, as it will
         * always be served from the backing SharedPreferences.
         * <p>
         * If not specified, the default is {@link #DEFAULT_MAX_CACHED_CONTEXTS} (5).
         *
         * @param maxCachedContexts The maximum number of evaluation contexts to cache; negative
         *                          values represent allowing an unlimited number of cached contexts
         * @return the builder
         */
        public Builder maxCachedContexts(int maxCachedContexts) {
            this.maxCachedContexts = maxCachedContexts;
            return this;
        }

        /**
         * Set to {@code true} to make the SDK provide unique keys for anonymous contexts.
         * <p>
         * If enabled, this option changes the SDK's behavior whenever the {@link LDContext} (as
         * given to methods like {@link LDClient#init(android.app.Application, LDConfig, LDContext, int)} or
         * {@link LDClient#identify(LDContext)}) has an {@link LDContext#isAnonymous()} property of
         * {@code true}, as follows:
         * <ul>
         * <li> The first time this happens in the application, the SDK will generate a
         * pseudo-random GUID and overwrite the context's {@code key} with this string. </li>
         * <li> The SDK will then cache this key so that the same key will be reused next time. </li>
         * <li> This uses the same persistent storage (shared preferences) mechanism as the caching
         * of flag values, so that the key can persist across restarts. </li>
         * </ul>
         * <p>
         * If you use multiple {@link ContextKind}s, this behavior is per-kind: that is, a separate
         * randomized key is generated and cached for each context kind.
         * <p>
         * Every {@link LDContext} must always have a key, even if the key will later be overwritten
         * by the SDK, so if you use this functionality you must still provide a placeholder key.
         * This ensures that if the SDK configuration is changed so {@code generateAnonymousKeys} is
         * no longer enabled, the SDK will still be able to use the context for evaluations.
         *
         * @param generateAnonymousKeys true to enable automatic anonymous key generation
         * @return the same builder
         * @since 4.0.0
         */
        public Builder generateAnonymousKeys(boolean generateAnonymousKeys) {
            this.generateAnonymousKeys = generateAnonymousKeys;
            return this;
        }

        /**
         * Specifies a custom data store. Deliberately package-private-- currently this is only
         * configurable for tests.
         *
         * @param persistentDataStore the store implementation
         * @return the same builder
         */
        Builder persistentDataStore(PersistentDataStore persistentDataStore) {
            this.persistentDataStore = persistentDataStore;
            return this;
        }

        /**
         * Specifies the implementation of logging to use.
         * <p>
         * The <a href="https://github.com/launchdarkly/java-logging"><code>com.launchdarkly.logging</code></a>
         * API defines the {@link LDLogAdapter} interface to specify where log output should be sent. By default,
         * it is set to {@link LDTimberLogging#adapter()}, meaning that output will be sent to the
         * <a href="https://github.com/JakeWharton/timber">Timber</a> framework and controlled by whatever Timber
         * configuration the application has created. You may change this to {@link LDAndroidLogging#adapter()}
         * to bypass Timber and use Android native logging directly; or, use the
         * {@link com.launchdarkly.logging.Logs} factory methods, or a custom implementation, to handle log
         * output differently.
         * <p>
         * Specifying {@code logAdapter(Logs.none())} completely disables log output.
         * <p>
         * For more about logging adapters,
         * see the <a href="https://docs.launchdarkly.com/sdk/features/logging#android">SDK reference guide</a>
         * and the <a href="https://launchdarkly.github.io/java-logging">API documentation</a> for
         * <code>com.launchdarkly.logging</code>.
         *
         * @param logAdapter an {@link LDLogAdapter} for the desired logging implementation
         * @return the builder
         * @since 3.2.0
         * @see #logLevel(LDLogLevel)
         * @see #loggerName(String)
         * @see LDTimberLogging
         * @see LDAndroidLogging
         * @see com.launchdarkly.logging.Logs
         */
        public Builder logAdapter(LDLogAdapter logAdapter) {
            this.logAdapter = logAdapter == null ? defaultLogAdapter() : logAdapter;
            return this;
        }

        /**
         * Specifies the lowest level of logging to enable.
         * <p>
         * This is only applicable when using an implementation of logging that does not have its own
         * external filter/configuration mechanism, such as {@link LDAndroidLogging}. It adds
         * a log level filter so that log messages at lower levels are suppressed. The default is
         * {@link LDLogLevel#INFO}, meaning that {@code INFO}, {@code WARN}, and {@code ERROR} levels
         * are enabled, but {@code DEBUG} is disabled. To enable {@code DEBUG} level as well:
         * <pre><code>
         *     LDConfig config = new LDConfig.Builder()
         *         .logAdapter(LDAndroidLogging.adapter())
         *         .level(LDLogLevel.DEBUG)
         *         .build();
         * </code></pre>
         * <p>
         * Or, to raise the logging threshold so that only WARN and ERROR levels are enabled, and
         * DEBUG and INFO are disabled:
         * <pre><code>
         *     LDConfig config = new LDConfig.Builder()
         *         .logAdapter(LDAndroidLogging.adapter())
         *         .level(LDLogLevel.WARN)
         *         .build();
         * </code></pre>
         * <p>
         * When using {@link LDTimberLogging}, Timber has its own mechanism for determining whether
         * to enable debug-level logging, so this method has no effect.
         *
         * @param logLevel the lowest level of logging to enable
         * @return the builder
         * @since 3.2.0
         * @see #logAdapter(LDLogAdapter)
         * @see #loggerName(String)
         */
        public Builder logLevel(LDLogLevel logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        /**
         * Specifies a custom logger name/tag for the SDK.
         * <p>
         * When using Timber or native Android logging, this becomes the tag for all SDK log output.
         * If you have specified a different logging implementation with {@link #logAdapter(LDLogAdapter)},
         * the meaning of the logger name depends on the logging framework.
         * <p>
         * If not specified, the default is "LaunchDarklySdk".
         *
         * @param loggerName the logger name or tag
         * @return the builder
         * @since 3.2.0
         * @see #logAdapter(LDLogAdapter)
         * @see #logLevel(LDLogLevel) 
         */
        public Builder loggerName(String loggerName) {
            this.loggerName = loggerName == null ? LDPackageConsts.DEFAULT_LOGGER_NAME : loggerName;
            return this;
        }

        private static LDLogAdapter defaultLogAdapter() {
            return LDTimberLogging.adapter();
        }

        /**
         * Returns the configured {@link LDConfig} object.
         * @return the configuration
         */
        public LDConfig build() {
            LDLogAdapter actualLogAdapter = Logs.level(logAdapter,
                logLevel == null ? DEFAULT_LOG_LEVEL : logLevel);
            // Note: if the log adapter is LDTimberLogging, then Logs.level has no effect - we will still
            // forward all of our logging to Timber, because it has its own mechanism for filtering out
            // debug logging. But if it is LDAndroidLogging or anything else, Logs.level ensures that no
            // output at a lower level than logLevel will be sent anywhere.

            HashMap<String, String> mobileKeys;
            if (secondaryMobileKeys == null) {
                mobileKeys = new HashMap<>();
            }
            else {
                mobileKeys = new HashMap<>(secondaryMobileKeys);
            }
            mobileKeys.put(primaryEnvironmentName, mobileKey);

            ServiceEndpoints serviceEndpoints =
                    (serviceEndpointsBuilder == null ? Components.serviceEndpoints() :
                            serviceEndpointsBuilder)
                            .createServiceEndpoints();

            ApplicationInfo applicationInfo = this.applicationInfoBuilder == null ?
                    null :
                    applicationInfoBuilder.createApplicationInfo();

            return new LDConfig(
                    mobileKeys,
                    serviceEndpoints,
                    applicationInfo,
                    this.dataSource == null ? Components.streamingDataSource() : this.dataSource,
                    this.events == null ? Components.sendEvents() : this.events,
                    (this.hooksConfigurationBuilder == null ? Components.hooks() : this.hooksConfigurationBuilder).build(),
                    (this.pluginsConfigurationBuilder == null ? Components.plugins() : this.pluginsConfigurationBuilder).build(),
                    this.http == null ? Components.httpConfiguration() : this.http,
                    offline,
                    disableBackgroundUpdating,
                    evaluationReasons,
                    diagnosticOptOut,
                    maxCachedContexts,
                    generateAnonymousKeys,
                    autoEnvAttributes,
                    persistentDataStore,
                    actualLogAdapter,
                    loggerName);
        }
    }
}
