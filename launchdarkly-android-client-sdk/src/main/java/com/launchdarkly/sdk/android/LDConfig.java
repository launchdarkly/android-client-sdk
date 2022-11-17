package com.launchdarkly.sdk.android;

import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.UserAttribute;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.integrations.HttpConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;

import java.util.Arrays;
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

    static final String DEFAULT_LOGGER_NAME = "LaunchDarklySdk";
    static final LDLogLevel DEFAULT_LOG_LEVEL = LDLogLevel.INFO;

    static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    static final String primaryEnvironmentName = "default";

    static final Uri DEFAULT_POLL_URI = Uri.parse("https://clientsdk.launchdarkly.com");
    static final Uri DEFAULT_EVENTS_URI = Uri.parse("https://mobile.launchdarkly.com");
    static final Uri DEFAULT_STREAM_URI = Uri.parse("https://clientstream.launchdarkly.com");

    static final int DEFAULT_MAX_CACHED_USERS = 5;

    private final Map<String, String> mobileKeys;

    private final Uri pollUri;
    private final Uri eventsUri;
    private final Uri streamUri;

    final ComponentConfigurer<DataSource> dataSource;
    final ComponentConfigurer<EventProcessor> events;
    final ComponentConfigurer<HttpConfiguration> http;

    private final boolean autoAliasingOptOut;
    private final boolean diagnosticOptOut;
    private final boolean disableBackgroundUpdating;
    private final boolean evaluationReasons;
    private final LDLogAdapter logAdapter;
    private final String loggerName;
    private final int maxCachedUsers;
    private final boolean offline;

    final Gson filteredEventGson;

    // deprecated properties that are now in sub-configuration builders
    private final boolean allAttributesPrivate;
    private final int backgroundPollingIntervalMillis;
    private final int connectionTimeoutMillis;
    private final int diagnosticRecordingIntervalMillis;
    private final int eventsCapacity;
    private final int eventsFlushIntervalMillis;
    private final LDHeaderUpdater headerTransform;
    private final boolean inlineUsersInEvents;
    private final int pollingIntervalMillis;
    private final Set<UserAttribute> privateAttributes;
    private final boolean stream;
    private final boolean useReport;
    private final String wrapperName;
    private final String wrapperVersion;

    LDConfig(Map<String, String> mobileKeys,
             Uri pollUri,
             Uri eventsUri,
             Uri streamUri,
             ComponentConfigurer<DataSource> dataSource,
             ComponentConfigurer<EventProcessor> events,
             ComponentConfigurer<HttpConfiguration> http,
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
             Set<UserAttribute> privateAttributes,
             boolean inlineUsersInEvents,
             boolean evaluationReasons,
             boolean diagnosticOptOut,
             int diagnosticRecordingIntervalMillis,
             String wrapperName,
             String wrapperVersion,
             int maxCachedUsers,
             LDHeaderUpdater headerTransform,
             boolean autoAliasingOptOut,
             LDLogAdapter logAdapter,
             String loggerName) {

        this.mobileKeys = mobileKeys;
        this.pollUri = pollUri;
        this.eventsUri = eventsUri;
        this.streamUri = streamUri;
        this.dataSource = dataSource;
        this.events = events;
        this.http = http;
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
        this.inlineUsersInEvents = inlineUsersInEvents;
        this.evaluationReasons = evaluationReasons;
        this.diagnosticOptOut = diagnosticOptOut;
        this.wrapperName = wrapperName;
        this.wrapperVersion = wrapperVersion;
        this.maxCachedUsers = maxCachedUsers;
        this.headerTransform = headerTransform;
        this.autoAliasingOptOut = autoAliasingOptOut;
        this.logAdapter = logAdapter;
        this.loggerName = loggerName;

        // The following temporary hack is for overriding several deprecated event-related setters
        // with the corresponding EventProcessorBuilder setters, if those were used. The problem is
        // that in the current SDK implementation, EventProcessor does not actually own the behavior
        // that those options are configuring (private attributes, and the diagnostic recording
        // interval), so we have to extract those values separately out of the config builder.
        boolean actualAllAttributesPrivate = allAttributesPrivate;
        Set<UserAttribute> actualPrivateAttributes = privateAttributes;
        int actualDiagnosticRecordingIntervalMillis = diagnosticRecordingIntervalMillis;
        if (events instanceof ComponentsImpl.EventProcessorBuilderImpl) {
            ComponentsImpl.EventProcessorBuilderImpl eventsBuilder =
                    (ComponentsImpl.EventProcessorBuilderImpl)events;
            actualAllAttributesPrivate = eventsBuilder.isAllAttributesPrivate();
            actualDiagnosticRecordingIntervalMillis = eventsBuilder.getDiagnosticRecordingIntervalMillis();
            actualPrivateAttributes = new HashSet<>();
            if (eventsBuilder.getPrivateAttributes() != null) {
                for (String a: eventsBuilder.getPrivateAttributes()) {
                    actualPrivateAttributes.add(UserAttribute.forName(a));
                }
            }
        }
        this.diagnosticRecordingIntervalMillis = actualDiagnosticRecordingIntervalMillis;

        this.filteredEventGson = new GsonBuilder()
                .registerTypeAdapter(LDUser.class,
                        new LDUtil.LDUserPrivateAttributesTypeAdapter(
                                actualAllAttributesPrivate,
                                actualPrivateAttributes
                        ))
                .create();
    }

    public String getMobileKey() {
        return mobileKeys.get(primaryEnvironmentName);
    }

    public Map<String, String> getMobileKeys() {
        return mobileKeys;
    }

    /**
     * Get the currently configured base URI for polling requests.
     *
     * @return the base URI configured to be used for poll requests.
     */
    public Uri getPollUri() {
        return pollUri;
    }

    public Uri getEventsUri() {
        return eventsUri;
    }

    /**
     * Returns the setting of {@link Builder#eventsCapacity(int)}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#events(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual event-related properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public int getEventsCapacity() {
        return eventsCapacity;
    }

    /**
     * Returns the setting of {@link Builder#eventsFlushIntervalMillis(int)}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#events(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual event-related properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public int getEventsFlushIntervalMillis() {
        return eventsFlushIntervalMillis;
    }

    /**
     * Returns the setting of {@link Builder#connectionTimeoutMillis(int)}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#http(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual HTTP-related properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public int getConnectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }

    public Uri getStreamUri() {
        return streamUri;
    }

    public boolean isOffline() {
        return offline;
    }

    /**
     * Returns the setting of {@link Builder#stream(boolean)}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#dataSource(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual data source properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public boolean isStream() {
        return stream;
    }

    /**
     * Returns the setting of {@link Builder#useReport(boolean)}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#http(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual HTTP-related properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public boolean isUseReport() {
        return useReport;
    }

    /**
     * Returns the setting of {@link Builder#pollingIntervalMillis(int)} ()}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#dataSource(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual data source properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public int getPollingIntervalMillis() {
        return pollingIntervalMillis;
    }

    /**
     * Returns the setting of {@link Builder#backgroundPollingIntervalMillis(int)} ()}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#dataSource(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual data source properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public int getBackgroundPollingIntervalMillis() {
        return backgroundPollingIntervalMillis;
    }

    public boolean isDisableBackgroundPolling() {
        return disableBackgroundUpdating;
    }

    /**
     * Returns the setting of {@link Builder#allAttributesPrivate()}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#events(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual event-related properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public boolean allAttributesPrivate() {
        return allAttributesPrivate;
    }

    /**
     * Returns the setting of {@link Builder#privateAttributes(UserAttribute...)}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#events(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual event-related properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public Set<UserAttribute> getPrivateAttributes() {
        return Collections.unmodifiableSet(privateAttributes);
    }

    /**
     * Returns a Gson instance that is configured to serialize event data. This is used internally
     * by the SDK; applications should not need to reference it.
     *
     * @return the Gson instance
     * @deprecated Direct access to this object is deprecated and will be removed in the future.
     */
    @Deprecated
    public Gson getFilteredEventGson() {
        return filteredEventGson;
    }

    /**
     * Returns the setting of {@link Builder#inlineUsersInEvents(boolean)}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#events(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual event-related properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public boolean inlineUsersInEvents() {
        return inlineUsersInEvents;
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

    int getMaxCachedUsers() {
        return maxCachedUsers;
    }

    /**
     * Returns the setting of {@link Builder#headerTransform(LDHeaderUpdater)}.
     * <p>
     * This is only applicable if you have used the deprecated builder method rather than
     * {@link Builder#http(ComponentConfigurer)}.
     * @return the property value
     * @deprecated This method will be removed in the future when individual HTTP-related properties
     *   are removed from the top-level configuration.
     */
    @Deprecated
    public LDHeaderUpdater getHeaderTransform() {
        return headerTransform;
    }

    boolean isAutoAliasingOptOut() {
        return autoAliasingOptOut;
    }

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

        private Uri pollUri = DEFAULT_POLL_URI;
        private Uri eventsUri = DEFAULT_EVENTS_URI;
        private Uri streamUri = DEFAULT_STREAM_URI;

        private ComponentConfigurer<DataSource> dataSource = null;
        private ComponentConfigurer<EventProcessor> events = null;
        private ComponentConfigurer<HttpConfiguration> http = null;

        private int eventsCapacity = EventProcessorBuilder.DEFAULT_CAPACITY;
        private int eventsFlushIntervalMillis = 0;
        private int connectionTimeoutMillis = HttpConfigurationBuilder.DEFAULT_CONNECT_TIMEOUT_MILLIS;
        private int pollingIntervalMillis = PollingDataSourceBuilder.DEFAULT_POLL_INTERVAL_MILLIS;
        private int backgroundPollingIntervalMillis = DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS;
        private int diagnosticRecordingIntervalMillis =
                EventProcessorBuilder.DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS;
        private int maxCachedUsers = DEFAULT_MAX_CACHED_USERS;

        private boolean offline = false;
        private boolean stream = true;
        private boolean disableBackgroundUpdating = false;
        private boolean useReport = false;
        private boolean diagnosticOptOut = false;

        private boolean allAttributesPrivate = false;
        private Set<UserAttribute> privateAttributes = new HashSet<>();

        private boolean inlineUsersInEvents = false;
        private boolean evaluationReasons = false;

        private String wrapperName;
        private String wrapperVersion;
        private LDHeaderUpdater headerTransform;
        private boolean autoAliasingOptOut = false;

        private LDLogAdapter logAdapter = defaultLogAdapter();
        private String loggerName = DEFAULT_LOGGER_NAME;
        private LDLogLevel logLevel = null;

        /**
         * Deprecated method for specifying that all user attributes other than the key should be
         * hidden from LaunchDarkly.
         * <p>
         * The preferred way to set this option now is with {@link EventProcessorBuilder}. Any
         * settings there will override this deprecated method.
         * <p>
         * @return the builder
         * @deprecated Use {@link #events(ComponentConfigurer)} and
         *   {@link EventProcessorBuilder#allAttributesPrivate(boolean)} instead.
         */
        @Deprecated
        public Builder allAttributesPrivate() {
            this.allAttributesPrivate = true;
            return this;
        }

        /**
         * Deprecated method for marking a set of attributes as private.
         * <p>
         * The preferred way to set this option now is with {@link EventProcessorBuilder}. Any
         * settings there will override this deprecated method.
         * <p>
         * This can also be specified on a per-user basis with {@link LDUser.Builder} methods like
         * {@link LDUser.Builder#privateName(String)}.
         *
         * @param privateAttributes a set of names that will be removed from user data sent to LaunchDarkly
         * @return the builder
         * @deprecated Use {@link Builder#events(ComponentConfigurer)} and
         *   {@link EventProcessorBuilder#privateAttributes(String...)} instead.
         */
        @Deprecated
        public Builder privateAttributes(UserAttribute... privateAttributes) {
            this.privateAttributes = new HashSet<>(Arrays.asList(privateAttributes));
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
         * Deprecated method for specifying whether to use the HTTP REPORT method.
         * <p>
         * The preferred way to set this option now is with {@link HttpConfigurationBuilder}. Any
         * settings there will override this deprecated method.
         *
         * @param useReport true if HTTP requests should use the REPORT verb
         * @return the builder
         * @deprecated Use {@link Builder#http(ComponentConfigurer)} and
         *   {@link HttpConfigurationBuilder#useReport(boolean)} instead.
         */
        @Deprecated
        public LDConfig.Builder useReport(boolean useReport) {
            this.useReport = useReport;
            return this;
        }

        /**
         * Set the base URI for polling requests to LaunchDarkly. You probably don't need to set this unless instructed by LaunchDarkly.
         *
         * @param pollUri the URI of the main LaunchDarkly service
         * @return the builder
         */
        public LDConfig.Builder pollUri(Uri pollUri) {
            this.pollUri = pollUri;
            return this;
        }

        /**
         * Set the events URI for sending analytics to LaunchDarkly. You probably don't need to set this unless instructed by LaunchDarkly.
         *
         * @param eventsUri the URI of the LaunchDarkly analytics event service
         * @return the builder
         */
        public LDConfig.Builder eventsUri(Uri eventsUri) {
            this.eventsUri = eventsUri;
            return this;
        }

        /**
         * Set the stream URI for connecting to the flag update stream. You probably don't need to set this unless instructed by LaunchDarkly.
         *
         * @param streamUri the URI of the LaunchDarkly streaming service
         * @return the builder
         */
        public LDConfig.Builder streamUri(Uri streamUri) {
            this.streamUri = streamUri;
            return this;
        }

        /**
         * Sets the configuration of the component that receives feature flag data from LaunchDarkly.
         * <p>
         * The default is {@link Components#streamingDataSource()}; you may instead use
         * {@link Components#pollingDataSource()}. See those methods for details on how to configure
         * them with options that are specific to streaming or polling mode.
         * <p>
         * Setting {@link LDConfig.Builder#offline(boolean)} to {@code true} will supersede this setting
         * and completely disable network requests.
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
        public LDConfig.Builder dataSource(ComponentConfigurer<DataSource> dataSourceConfigurer) {
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
        public LDConfig.Builder events(ComponentConfigurer<EventProcessor> eventsConfigurer) {
            this.events = eventsConfigurer;
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
         * Deprecated method for setting the capacity of the event buffer.
         * <p>
         * The preferred way to set this option now is with {@link EventProcessorBuilder}. Any
         * settings there will override this deprecated method.
         * <p>
         * The default value is {@link EventProcessorBuilder#DEFAULT_CAPACITY}.
         *
         * @param eventsCapacity the capacity of the event buffer
         * @return the builder
         * @see #eventsFlushIntervalMillis(int)
         * @deprecated Use {@link Builder#events(ComponentConfigurer)} and
         *   {@link EventProcessorBuilder#capacity(int)} instead.
         */
        @Deprecated
        public LDConfig.Builder eventsCapacity(int eventsCapacity) {
            this.eventsCapacity = eventsCapacity;
            return this;
        }

        /**
         * Deprecated method for setting the maximum amount of time to wait in between sending
         * analytics events to LaunchDarkly.
         * <p>
         * The preferred way to set this option now is with {@link EventProcessorBuilder}. Any
         * settings there will override this deprecated method.
         * <p>
         * The default value is {@link EventProcessorBuilder#DEFAULT_FLUSH_INTERVAL_MILLIS}.
         *
         * @param eventsFlushIntervalMillis the interval between event flushes, in milliseconds
         * @return the builder
         * @see #eventsCapacity(int)
         * @deprecated Use {@link Builder#events(ComponentConfigurer)} and
         *   {@link EventProcessorBuilder#flushIntervalMillis(int)} instead.
         */
        @Deprecated
        public LDConfig.Builder eventsFlushIntervalMillis(int eventsFlushIntervalMillis) {
            this.eventsFlushIntervalMillis = eventsFlushIntervalMillis;
            return this;
        }

        /**
         * Deprecated method for setting the connection timeout.
         * <p>
         * The preferred way to set this option now is with {@link HttpConfigurationBuilder}. Any
         * settings there will override this deprecated method.
         *
         * @param connectionTimeoutMillis the connection timeout, in milliseconds
         * @return the builder
         * @deprecated Use {@link Builder#http(ComponentConfigurer)} and
         *   {@link HttpConfigurationBuilder#connectTimeoutMillis(int)} instead.
         */
        @Deprecated
        public LDConfig.Builder connectionTimeoutMillis(int connectionTimeoutMillis) {
            this.connectionTimeoutMillis = connectionTimeoutMillis;
            return this;
        }

        /**
         * Deprecated method for enabling or disabling real-time streaming flag updates.
         * <p>
         * The preferred way to set this option now is with {@link StreamingDataSourceBuilder}. Any
         * settings there will override this deprecated method. Setting this option to {@code false}
         * is equivalent to calling {@code builder.dataSource(Components.pollingDataSource())}.
         * <p>
         * By default, streaming is enabled.
         *
         * @param enabled true if streaming should be enabled
         * @return the builder
         * @deprecated Use {@link Builder#dataSource(ComponentConfigurer)} with either
         *   {@link Components#streamingDataSource()} or {@link Components#pollingDataSource()}
         *   instead.
         */
        @Deprecated
        public LDConfig.Builder stream(boolean enabled) {
            this.stream = enabled;
            return this;
        }

        /**
         * Deprecated method for setting the interval in between feature flag updates, when
         * streaming mode is disabled.
         * <p>
         * The preferred way to set this option now is with {@link PollingDataSourceBuilder}. Any
         * settings there will override this deprecated method.
         * <p>
         * The default value is {@link PollingDataSourceBuilder#DEFAULT_POLL_INTERVAL_MILLIS}.
         *
         * @param pollingIntervalMillis the feature flag polling interval, in milliseconds
         * @return the builder
         * @deprecated Use {@link Builder#dataSource(ComponentConfigurer)} and
         *   {@link PollingDataSourceBuilder#pollIntervalMillis(int)} instead.
         */
        @Deprecated
        public LDConfig.Builder pollingIntervalMillis(int pollingIntervalMillis) {
            this.pollingIntervalMillis = pollingIntervalMillis;
            return this;
        }

        /**
         * Deprecated method for setting how often the client will poll for flag updates when your
         * application is in the background.
         * <p>
         * The preferred way to set this option now is with {@link StreamingDataSourceBuilder} or
         * {@link PollingDataSourceBuilder} (depending on whether you want the SDK to use streaming
         * or polling when it is in the foreground). Any settings there will override this
         * deprecated method.
         * <p>
         * The default value is {@link LDConfig#DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS}.
         *
         * @param backgroundPollingIntervalMillis the feature flag polling interval when in the background,
         *                                        in milliseconds
         * @return the builder
         * @deprecated Use {@link Builder#dataSource(ComponentConfigurer)} and either
         *   {@link StreamingDataSourceBuilder#backgroundPollIntervalMillis(int)} or
         *   {@link PollingDataSourceBuilder#backgroundPollIntervalMillis(int)} instead.
         */
        @Deprecated
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
         * Deprecated method for specifying whether events sent to the server will always include
         * the full user object.
         * <p>
         * The preferred way to set this option now is with {@link EventProcessorBuilder}. Any
         * settings there will override this deprecated method.
         * <p>
         * This defaults to false in order to reduce network bandwidth.
         *
         * @param inlineUsersInEvents true if all user properties should be included in events
         * @return the builder
         * @deprecated Use {@link Builder#events(ComponentConfigurer)} and
         *   {@link EventProcessorBuilder#inlineUsers(boolean)} instead.
         */
        @Deprecated
        public LDConfig.Builder inlineUsersInEvents(boolean inlineUsersInEvents) {
            this.inlineUsersInEvents = inlineUsersInEvents;
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
         * Deprecatd method for setting the interval at which periodic diagnostic data is sent.
         * <p>
         * The preferred way to set this option now is with {@link EventProcessorBuilder}. Any
         * settings there will override this deprecated method.
         *
         * @param diagnosticRecordingIntervalMillis the diagnostics interval in milliseconds
         * @return the builder
         * @deprecated Use {@link Builder#events(ComponentConfigurer)} and
         *   {@link EventProcessorBuilder#diagnosticRecordingIntervalMillis(int)} instead.
         * @see #diagnosticOptOut(boolean)
         */
        @Deprecated
        public LDConfig.Builder diagnosticRecordingIntervalMillis(int diagnosticRecordingIntervalMillis) {
            this.diagnosticRecordingIntervalMillis = diagnosticRecordingIntervalMillis;
            return this;
        }

        /**
         * Deprecated method for setting a wrapper library name to include in User-Agent headers.
         * <p>
         * The preferred way to set this option now is with {@link HttpConfigurationBuilder}. Any
         * settings there will override this deprecated method.
         *
         * @param wrapperName an identifying name for the wrapper library
         * @return the builder
         * @deprecated Use {@link Builder#http(ComponentConfigurer)} and
         *   {@link HttpConfigurationBuilder#wrapper(String, String)} instead.
         */
        @Deprecated
        public LDConfig.Builder wrapperName(String wrapperName) {
            this.wrapperName = wrapperName;
            return this;
        }

        /**
         * Deprecated method for setting a wrapper library version to include in User-Agent headers.
         * <p>
         * The preferred way to set this option now is with {@link HttpConfigurationBuilder}. Any
         * settings there will override this deprecated method.
         *
         * @param wrapperVersion a version string for the wrapper library
         * @return the builder
         * @deprecated Use {@link Builder#http(ComponentConfigurer)} and
         *   {@link HttpConfigurationBuilder#wrapper(String, String)} instead.
         */
        @Deprecated
        public LDConfig.Builder wrapperVersion(String wrapperVersion) {
            this.wrapperVersion = wrapperVersion;
            return this;
        }

        /**
         * Sets the maximum number of users to cache the flag values for locally in the device's
         * SharedPreferences.
         * <p>
         * Note that the active user is not considered part of this limit, as it will always be
         * served from the backing SharedPreferences.
         *
         * @param maxCachedUsers The maximum number of users to cache, negative values represent
         *                       allowing an unlimited number of cached users.
         * @return the builder
         */
        public LDConfig.Builder maxCachedUsers(int maxCachedUsers) {
            this.maxCachedUsers = maxCachedUsers;
            return this;
        }

        /**
         * Enable this opt-out to disable sending an automatic alias event when {@link LDClient#identify(LDUser)} is
         * called with a non-anonymous user when the current user is anonymous.
         *
         * @param autoAliasingOptOut Whether the automatic aliasing feature should be disabled
         * @return the builder
         */
        public LDConfig.Builder autoAliasingOptOut(boolean autoAliasingOptOut) {
            this.autoAliasingOptOut = autoAliasingOptOut;
            return this;
        }

        /**
         * Deprecated method for dynamically modifying request headers.
         * <p>
         * The preferred way to set this option now is with {@link HttpConfigurationBuilder}. Any
         * settings there will override this deprecated method.
         *
         * @param headerTransform the transformation to apply to requests
         * @return the builder
         * @deprecated Use {@link Builder#http(ComponentConfigurer)} and
         *   {@link HttpConfigurationBuilder#headerTransform(LDHeaderUpdater)} instead.
         */
        @Deprecated
        public LDConfig.Builder headerTransform(LDHeaderUpdater headerTransform) {
            this.headerTransform = headerTransform;
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

            if (diagnosticRecordingIntervalMillis < EventProcessorBuilder.MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS) {
                logger.warn(
                        "diagnosticRecordingIntervalMillis was set to %s, lower than the minimum allowed (%s). Ignoring and using minimum value.",
                        diagnosticRecordingIntervalMillis, EventProcessorBuilder.MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS);
                diagnosticRecordingIntervalMillis = EventProcessorBuilder.MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS;
            }

            HashMap<String, String> mobileKeys;
            if (secondaryMobileKeys == null) {
                mobileKeys = new HashMap<>();
            }
            else {
                mobileKeys = new HashMap<>(secondaryMobileKeys);
            }
            mobileKeys.put(primaryEnvironmentName, mobileKey);

            ComponentConfigurer<DataSource> dataSourceConfig = this.dataSource;
            if (dataSourceConfig == null) {
                // Copy the deprecated properties to the new data source configuration builder.
                // There is some additional validation logic here that is specific to the
                // deprecated property setters; the new configuration builder, in keeping with the
                // standard behavior of other configuration builders in the Java and Android SDKs,
                // doesn't log such messages.

                if (!disableBackgroundUpdating) {
                    if (backgroundPollingIntervalMillis < MIN_BACKGROUND_POLL_INTERVAL_MILLIS) {
                        logger.warn(
                                "BackgroundPollingIntervalMillis: {} was set below the minimum allowed: {}. Ignoring and using minimum value.",
                                backgroundPollingIntervalMillis, MIN_BACKGROUND_POLL_INTERVAL_MILLIS);
                        backgroundPollingIntervalMillis = MIN_BACKGROUND_POLL_INTERVAL_MILLIS;
                    }
                }

                if (stream) {
                    dataSourceConfig = Components.streamingDataSource()
                            .backgroundPollIntervalMillis(backgroundPollingIntervalMillis);
                } else {
                    if (pollingIntervalMillis < PollingDataSourceBuilder.DEFAULT_POLL_INTERVAL_MILLIS) {
                        // the default is also the minimum
                        logger.warn(
                                "setPollingIntervalMillis: {} was set below the allowed minimum of: {}. Ignoring and using minimum value.",
                                pollingIntervalMillis, PollingDataSourceBuilder.DEFAULT_POLL_INTERVAL_MILLIS);
                        pollingIntervalMillis = PollingDataSourceBuilder.DEFAULT_POLL_INTERVAL_MILLIS;
                    }

                    if (!disableBackgroundUpdating && backgroundPollingIntervalMillis < pollingIntervalMillis) {
                        logger.warn(
                                "BackgroundPollingIntervalMillis: {} was set below the foreground polling interval: {}. Ignoring and using minimum value for background polling.",
                                backgroundPollingIntervalMillis, pollingIntervalMillis);
                        backgroundPollingIntervalMillis = MIN_BACKGROUND_POLL_INTERVAL_MILLIS;
                    }

                    if (eventsFlushIntervalMillis == 0) {
                        // This behavior is retained for historical reasons; the newer configuration
                        // builder does not modify properties like this that are outside its scope.
                        eventsFlushIntervalMillis = pollingIntervalMillis;
                    }

                    dataSourceConfig = Components.pollingDataSource()
                            .backgroundPollIntervalMillis(backgroundPollingIntervalMillis)
                            .pollIntervalMillis(pollingIntervalMillis);
                }
            }

            if (eventsFlushIntervalMillis == 0) {
                eventsFlushIntervalMillis = EventProcessorBuilder.DEFAULT_FLUSH_INTERVAL_MILLIS;
                // this is a normal occurrence, so don't log a warning about it
            }

            ComponentConfigurer<EventProcessor> eventsConfig = this.events;
            if (eventsConfig == null) {
                // Copy the deprecated properties to the new events configuration builder.
                EventProcessorBuilder eventsBuilder = Components.sendEvents()
                        .allAttributesPrivate(allAttributesPrivate)
                        .capacity(eventsCapacity)
                        .diagnosticRecordingIntervalMillis(diagnosticRecordingIntervalMillis)
                        .flushIntervalMillis(eventsFlushIntervalMillis)
                        .inlineUsers(inlineUsersInEvents);
                if (privateAttributes != null) {
                    eventsBuilder.privateAttributes(privateAttributes.toArray(new UserAttribute[privateAttributes.size()]));
                }
                eventsConfig = eventsBuilder;
            }

            ComponentConfigurer<HttpConfiguration> httpConfig = this.http;
            if (httpConfig == null) {
                // Copy the deprecated properties to the new HTTP configuration builder.
                HttpConfigurationBuilder httpBuilder = Components.httpConfiguration()
                        .connectTimeoutMillis(connectionTimeoutMillis)
                        .headerTransform(headerTransform)
                        .useReport(useReport)
                        .wrapper(wrapperName, wrapperVersion);
                httpConfig = httpBuilder;
            }

            return new LDConfig(
                    mobileKeys,
                    pollUri,
                    eventsUri,
                    streamUri,
                    dataSourceConfig,
                    eventsConfig,
                    httpConfig,
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
                    inlineUsersInEvents,
                    evaluationReasons,
                    diagnosticOptOut,
                    diagnosticRecordingIntervalMillis,
                    wrapperName,
                    wrapperVersion,
                    maxCachedUsers,
                    headerTransform,
                    autoAliasingOptOut,
                    actualLogAdapter,
                    loggerName);
        }
    }
}
