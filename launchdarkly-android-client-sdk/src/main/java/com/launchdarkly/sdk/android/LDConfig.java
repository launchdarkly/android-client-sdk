package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

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

    static final String DEFAULT_LOGGER_NAME = "LaunchDarklySdk";
    static final LDLogLevel DEFAULT_LOG_LEVEL = LDLogLevel.INFO;

    static final String USER_AGENT_HEADER_VALUE = "AndroidClient/" + BuildConfig.VERSION_NAME;
    static final String AUTH_SCHEME = "api_key ";
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    static final String primaryEnvironmentName = "default";

    static final int DEFAULT_EVENTS_CAPACITY = 100;
    static final int DEFAULT_MAX_CACHED_CONTEXTS = 5;
    static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 30_000; // 30 seconds
    static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10_000; // 10 seconds
    static final int DEFAULT_POLLING_INTERVAL_MILLIS = 300_000; // 5 minutes
    static final int DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS = 3_600_000; // 1 hour
    static final int MIN_BACKGROUND_POLLING_INTERVAL_MILLIS = 900_000; // 15 minutes
    static final int MIN_POLLING_INTERVAL_MILLIS = 300_000; // 5 minutes
    static final int DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS = 900_000; // 15 minutes
    static final int MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS = 300_000; // 5 minutes

    private final Map<String, String> mobileKeys;

    final ServiceEndpoints serviceEndpoints;

    private final int eventsCapacity;
    private final int eventsFlushIntervalMillis;
    private final int connectionTimeoutMillis;
    private final int pollingIntervalMillis;
    private final int backgroundPollingIntervalMillis;
    private final int diagnosticRecordingIntervalMillis;
    private final int maxCachedContexts;

    private final boolean stream;
    private final boolean offline;
    private final boolean disableBackgroundUpdating;
    private final boolean useReport;
    private final boolean diagnosticOptOut;

    private final boolean allAttributesPrivate;
    private final Set<AttributeRef> privateAttributes;

    private final boolean evaluationReasons;

    private final String wrapperName;
    private final String wrapperVersion;

    private final LDHeaderUpdater headerTransform;

    private final boolean generateAnonymousKeys;

    private final PersistentDataStore persistentDataStore; // configurable for testing only

    private final LDLogAdapter logAdapter;
    private final String loggerName;

    LDConfig(Map<String, String> mobileKeys,
             ServiceEndpoints serviceEndpoints,
             int eventsCapacity,
             int eventsFlushIntervalMillis,
             int connectionTimeoutMillis,
             boolean offline,
             boolean stream,
             int pollingIntervalMillis,
             int backgroundPollingIntervalMillis,
             boolean disableBackgroundUpdating,
             boolean useReport,
             boolean allAttributesPrivate,
             Set<AttributeRef> privateAttributes,
             boolean evaluationReasons,
             boolean diagnosticOptOut,
             int diagnosticRecordingIntervalMillis,
             String wrapperName,
             String wrapperVersion,
             int maxCachedContexts,
             LDHeaderUpdater headerTransform,
             boolean generateAnonymousKeys,
             PersistentDataStore persistentDataStore,
             LDLogAdapter logAdapter,
             String loggerName) {
        this.mobileKeys = mobileKeys;
        this.serviceEndpoints = serviceEndpoints;
        this.eventsCapacity = eventsCapacity;
        this.eventsFlushIntervalMillis = eventsFlushIntervalMillis;
        this.connectionTimeoutMillis = connectionTimeoutMillis;
        this.offline = offline;
        this.stream = stream;
        this.pollingIntervalMillis = pollingIntervalMillis;
        this.backgroundPollingIntervalMillis = backgroundPollingIntervalMillis;
        this.disableBackgroundUpdating = disableBackgroundUpdating;
        this.useReport = useReport;
        this.allAttributesPrivate = allAttributesPrivate;
        this.privateAttributes = privateAttributes;
        this.evaluationReasons = evaluationReasons;
        this.diagnosticOptOut = diagnosticOptOut;
        this.diagnosticRecordingIntervalMillis = diagnosticRecordingIntervalMillis;
        this.wrapperName = wrapperName;
        this.wrapperVersion = wrapperVersion;
        this.maxCachedContexts = maxCachedContexts;
        this.headerTransform = headerTransform;
        this.generateAnonymousKeys = generateAnonymousKeys;
        this.persistentDataStore = persistentDataStore;
        this.logAdapter = logAdapter;
        this.loggerName = loggerName;
    }

    public String getMobileKey() {
        return mobileKeys.get(primaryEnvironmentName);
    }

    public Map<String, String> getMobileKeys() {
        return mobileKeys;
    }

    public int getEventsCapacity() {
        return eventsCapacity;
    }

    public int getEventsFlushIntervalMillis() {
        return eventsFlushIntervalMillis;
    }

    public int getConnectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }

    public boolean isOffline() {
        return offline;
    }

    public boolean isStream() {
        return stream;
    }

    public boolean isUseReport() {
        return useReport;
    }

    public int getPollingIntervalMillis() {
        return pollingIntervalMillis;
    }

    public int getBackgroundPollingIntervalMillis() {
        return backgroundPollingIntervalMillis;
    }

    public boolean isDisableBackgroundPolling() {
        return disableBackgroundUpdating;
    }

    public boolean allAttributesPrivate() {
        return allAttributesPrivate;
    }

    public Set<AttributeRef> getPrivateAttributes() {
        return Collections.unmodifiableSet(privateAttributes);
    }

    public boolean isEvaluationReasons() {
        return evaluationReasons;
    }

    boolean getDiagnosticOptOut() {
        return diagnosticOptOut;
    }

    int getDiagnosticRecordingIntervalMillis() {
        return diagnosticRecordingIntervalMillis;
    }

    String getWrapperName() {
        return wrapperName;
    }

    String getWrapperVersion() {
        return wrapperVersion;
    }

    int getMaxCachedContexts() {
        return maxCachedContexts;
    }

    public LDHeaderUpdater getHeaderTransform() {
        return headerTransform;
    }

    public boolean isGenerateAnonymousKeys() { return generateAnonymousKeys; }

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
        private String mobileKey;
        private Map<String, String> secondaryMobileKeys;

        private ServiceEndpointsBuilder serviceEndpointsBuilder;

        private int eventsCapacity = DEFAULT_EVENTS_CAPACITY;
        private int eventsFlushIntervalMillis = 0;
        private int connectionTimeoutMillis = DEFAULT_CONNECTION_TIMEOUT_MILLIS;
        private int pollingIntervalMillis = DEFAULT_POLLING_INTERVAL_MILLIS;
        private int backgroundPollingIntervalMillis = DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS;
        private int diagnosticRecordingIntervalMillis = DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS;
        private int maxCachedContexts = DEFAULT_MAX_CACHED_CONTEXTS;

        private boolean offline = false;
        private boolean stream = true;
        private boolean disableBackgroundUpdating = false;
        private boolean useReport = false;
        private boolean diagnosticOptOut = false;

        private boolean allAttributesPrivate = false;
        private Set<AttributeRef> privateAttributes = new HashSet<>();

        private boolean evaluationReasons = false;

        private String wrapperName;
        private String wrapperVersion;
        private LDHeaderUpdater headerTransform;
        private boolean generateAnonymousKeys;

        private PersistentDataStore persistentDataStore;

        private LDLogAdapter logAdapter = defaultLogAdapter();
        private String loggerName = DEFAULT_LOGGER_NAME;
        private LDLogLevel logLevel = null;

        /**
         * Specifies that context attributes (other than the key) should be hidden from LaunchDarkly.
         * If this is set, all context attribute values will be private, not just the attributes
         * specified in {@link #privateAttributes(String...)}.
         *
         * @return the builder
         */
        public Builder allAttributesPrivate() {
            this.allAttributesPrivate = true;
            return this;
        }

        /**
         * Marks a set of attribute names or subproperties as private.
         * <p>
         * Any contexts sent to LaunchDarkly with this configuration active will have attributes with these
         * names removed. This is in addition to any attributes that were marked as private for an
         * individual context with {@link com.launchdarkly.sdk.ContextBuilder} methods.
         * <p>
         * If and only if a parameter starts with a slash, it is interpreted as a slash-delimited path that
         * can denote a nested property within a JSON object. For instance, "/address/street" means that if
         * there is an attribute called "address" that is a JSON object, and one of the object's properties
         * is "street", the "street" property will be redacted from the analytics data but other properties
         * within "address" will still be sent. This syntax also uses the JSON Pointer convention of escaping
         * a literal slash character as "~1" and a tilde as "~0".
         * <p>
         * This method replaces any previous private attributes that were set on the same builder, rather
         * than adding to them.
         *
         * @param privateAttributes a set of names or paths that will be removed from context data set to LaunchDarkly
         * @return the builder
         */
        public Builder privateAttributes(String... privateAttributes) {
            this.privateAttributes = new HashSet<>();
            for (String a: privateAttributes) {
                this.privateAttributes.add(AttributeRef.fromPath(a));
            }
            return this;
        }

        /**
         * Sets the key for authenticating with LaunchDarkly. This is required unless you're using the client in offline mode.
         *
         * @param mobileKey Get this from the LaunchDarkly web app under Team Settings.
         * @return the builder
         */
        public LDConfig.Builder mobileKey(String mobileKey) {
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
        public LDConfig.Builder secondaryMobileKeys(Map<String, String> secondaryMobileKeys) {
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
         * Sets the flag for choosing the REPORT api call.  The default is GET.
         * Do not use unless advised by LaunchDarkly.
         *
         * @param useReport true if HTTP requests should use the REPORT verb
         * @return the builder
         */
        public LDConfig.Builder useReport(boolean useReport) {
            this.useReport = useReport;
            return this;
        }

        /**
         * Set the capacity of the event buffer. The client buffers up to this many events in memory before flushing.
         * If the capacity is exceeded before the buffer is flushed, events will be discarded. Increasing the capacity
         * means that events are less likely to be discarded, at the cost of consuming more memory.
         * <p>
         * The default value is {@link #DEFAULT_EVENTS_CAPACITY}.
         *
         * @param eventsCapacity the capacity of the event buffer
         * @return the builder
         * @see #eventsFlushIntervalMillis(int)
         */
        public LDConfig.Builder eventsCapacity(int eventsCapacity) {
            this.eventsCapacity = eventsCapacity;
            return this;
        }

        /**
         * Sets the maximum amount of time to wait in between sending analytics events to LaunchDarkly.
         * <p>
         * The default value is {@link #DEFAULT_FLUSH_INTERVAL_MILLIS}.
         *
         * @param eventsFlushIntervalMillis the interval between event flushes, in milliseconds
         * @return the builder
         * @see #eventsCapacity(int)
         */
        public LDConfig.Builder eventsFlushIntervalMillis(int eventsFlushIntervalMillis) {
            this.eventsFlushIntervalMillis = eventsFlushIntervalMillis;
            return this;
        }


        /**
         * Sets the timeout when connecting to LaunchDarkly.
         * <p>
         * The default value is {@link #DEFAULT_CONNECTION_TIMEOUT_MILLIS}.
         *
         * @param connectionTimeoutMillis the connection timeout, in milliseconds
         * @return the builder
         */
        public LDConfig.Builder connectionTimeoutMillis(int connectionTimeoutMillis) {
            this.connectionTimeoutMillis = connectionTimeoutMillis;
            return this;
        }


        /**
         * Enables or disables real-time streaming flag updates.  By default, streaming is enabled.
         * When disabled, an efficient caching polling mechanism is used.
         *
         * @param enabled true if streaming should be enabled
         * @return the builder
         */
        public LDConfig.Builder stream(boolean enabled) {
            this.stream = enabled;
            return this;
        }

        /**
         * Sets the interval in between feature flag updates, when streaming mode is disabled.
         * This is ignored unless {@link #stream(boolean)} is set to {@code true}. When set, it
         * will also change the default value for {@link #eventsFlushIntervalMillis(int)} to the
         * same value.
         * <p>
         * The default value is {@link LDConfig#DEFAULT_POLLING_INTERVAL_MILLIS}.
         *
         * @param pollingIntervalMillis the feature flag polling interval, in milliseconds
         * @return the builder
         */
        public LDConfig.Builder pollingIntervalMillis(int pollingIntervalMillis) {
            this.pollingIntervalMillis = pollingIntervalMillis;
            return this;
        }

        /**
         * Sets how often the client will poll for flag updates when your application is in the background.
         * <p>
         * The default value is {@link LDConfig#DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS}.
         *
         * @param backgroundPollingIntervalMillis the feature flag polling interval when in the background,
         *                                        in milliseconds
         * @return the builder
         */
        public LDConfig.Builder backgroundPollingIntervalMillis(int backgroundPollingIntervalMillis) {
            this.backgroundPollingIntervalMillis = backgroundPollingIntervalMillis;
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
        public LDConfig.Builder disableBackgroundUpdating(boolean disableBackgroundUpdating) {
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
        public LDConfig.Builder offline(boolean offline) {
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
        public LDConfig.Builder evaluationReasons(boolean evaluationReasons) {
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
        public LDConfig.Builder diagnosticOptOut(boolean diagnosticOptOut) {
            this.diagnosticOptOut = diagnosticOptOut;
            return this;
        }

        /**
         * Sets the interval at which periodic diagnostic data is sent. The default is every 15 minutes (900,000
         * milliseconds) and the minimum value is 300,000 (5 minutes).
         *
         * @see #diagnosticOptOut(boolean) for more information on the diagnostics data being sent.
         *
         * @param diagnosticRecordingIntervalMillis the diagnostics interval in milliseconds
         * @return the builder
         */
        public LDConfig.Builder diagnosticRecordingIntervalMillis(int diagnosticRecordingIntervalMillis) {
            this.diagnosticRecordingIntervalMillis = diagnosticRecordingIntervalMillis;
            return this;
        }

        /**
         * For use by wrapper libraries to set an identifying name for the wrapper being used. This will be sent in
         * User-Agent headers during requests to the LaunchDarkly servers to allow recording metrics on the usage of
         * these wrapper libraries.
         *
         * @param wrapperName An identifying name for the wrapper library
         * @return the builder
         */
        public LDConfig.Builder wrapperName(String wrapperName) {
            this.wrapperName = wrapperName;
            return this;
        }

        /**
         * For use by wrapper libraries to report the version of the library in use. If the wrapper
         * name has not been set with {@link #wrapperName(String)} this field will be ignored.
         * Otherwise the version string will be included in the User-Agent headers along with the
         * wrapperName during requests to the LaunchDarkly servers.
         *
         * @param wrapperVersion Version string for the wrapper library
         * @return the builder
         */
        public LDConfig.Builder wrapperVersion(String wrapperVersion) {
            this.wrapperVersion = wrapperVersion;
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
        public LDConfig.Builder maxCachedContexts(int maxCachedContexts) {
            this.maxCachedContexts = maxCachedContexts;
            return this;
        }

        /**
         * Provides a callback for dynamically modifying headers used on requests to the LaunchDarkly service.
         *
         * @param headerTransform the transformation to apply to requests
         * @return the builder
         */
        public LDConfig.Builder headerTransform(LDHeaderUpdater headerTransform) {
            this.headerTransform = headerTransform;
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
         * no longer enabled, the SDK will still be able to use the context for evaluations. (The
         * legacy {@link LDUser} type does allow a null key in this case.)
         *
         * @param generateAnonymousKeys true to enable automatic anonymous key generation
         * @return the same builder
         * @since 4.0.0
         */
        public LDConfig.Builder generateAnonymousKeys(boolean generateAnonymousKeys) {
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
        LDConfig.Builder persistentDataStore(PersistentDataStore persistentDataStore) {
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
        public LDConfig.Builder logAdapter(LDLogAdapter logAdapter) {
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
        public LDConfig.Builder logLevel(LDLogLevel logLevel) {
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
        public LDConfig.Builder loggerName(String loggerName) {
            this.loggerName = loggerName == null ? DEFAULT_LOGGER_NAME : loggerName;
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

            LDLogger logger = LDLogger.withAdapter(actualLogAdapter, loggerName);

            if (!stream) {
                if (pollingIntervalMillis < MIN_POLLING_INTERVAL_MILLIS) {
                    logger.warn(
                            "setPollingIntervalMillis: {} was set below the allowed minimum of: {}. Ignoring and using minimum value.",
                            pollingIntervalMillis, MIN_POLLING_INTERVAL_MILLIS);
                    pollingIntervalMillis = MIN_POLLING_INTERVAL_MILLIS;
                }

                if (!disableBackgroundUpdating && backgroundPollingIntervalMillis < pollingIntervalMillis) {
                    logger.warn(
                            "BackgroundPollingIntervalMillis: {} was set below the foreground polling interval: {}. Ignoring and using minimum value for background polling.",
                            backgroundPollingIntervalMillis, pollingIntervalMillis);
                    backgroundPollingIntervalMillis = MIN_BACKGROUND_POLLING_INTERVAL_MILLIS;
                }

                if (eventsFlushIntervalMillis == 0) {
                    eventsFlushIntervalMillis = pollingIntervalMillis;
                    // this is a normal occurrence, so don't log a warning about it
                }
            }

            if (!disableBackgroundUpdating) {
                if (backgroundPollingIntervalMillis < MIN_BACKGROUND_POLLING_INTERVAL_MILLIS) {
                    logger.warn(
                            "BackgroundPollingIntervalMillis: {} was set below the minimum allowed: {}. Ignoring and using minimum value.",
                            backgroundPollingIntervalMillis, MIN_BACKGROUND_POLLING_INTERVAL_MILLIS);
                    backgroundPollingIntervalMillis = MIN_BACKGROUND_POLLING_INTERVAL_MILLIS;
                }
            }

            if (eventsFlushIntervalMillis == 0) {
                eventsFlushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS; // this is a normal occurrence, so don't log a warning about it
            }

            if (diagnosticRecordingIntervalMillis < MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS) {
                logger.warn(
                        "diagnosticRecordingIntervalMillis was set to {}, lower than the minimum allowed ({}). Ignoring and using minimum value.",
                        diagnosticRecordingIntervalMillis, MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS);
                diagnosticRecordingIntervalMillis = MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS;
            }

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

            return new LDConfig(
                    mobileKeys,
                    serviceEndpoints,
                    eventsCapacity,
                    eventsFlushIntervalMillis,
                    connectionTimeoutMillis,
                    offline,
                    stream,
                    pollingIntervalMillis,
                    backgroundPollingIntervalMillis,
                    disableBackgroundUpdating,
                    useReport,
                    allAttributesPrivate,
                    privateAttributes,
                    evaluationReasons,
                    diagnosticOptOut,
                    diagnosticRecordingIntervalMillis,
                    wrapperName,
                    wrapperVersion,
                    maxCachedContexts,
                    headerTransform,
                    generateAnonymousKeys,
                    persistentDataStore,
                    actualLogAdapter,
                    loggerName);
        }
    }
}
