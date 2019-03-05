package com.launchdarkly.android;

import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.Request;
import timber.log.Timber;

/**
 * This class exposes advanced configuration options for {@link LDClient}. Instances of this class
 * must be constructed with {@link LDConfig.Builder}.
 */
public class LDConfig {

    static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";
    static final String USER_AGENT_HEADER_VALUE = "AndroidClient/" + BuildConfig.VERSION_NAME;
    static final String AUTH_SCHEME = "api_key ";
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    static final String primaryEnvironmentName = "default";

    static final Uri DEFAULT_BASE_URI = Uri.parse("https://app.launchdarkly.com");
    static final Uri DEFAULT_EVENTS_URI = Uri.parse("https://mobile.launchdarkly.com/mobile");
    static final Uri DEFAULT_STREAM_URI = Uri.parse("https://clientstream.launchdarkly.com");

    static final int DEFAULT_EVENTS_CAPACITY = 100;
    static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 30_000; // 30 seconds
    static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10_000; // 10 seconds
    static final int DEFAULT_POLLING_INTERVAL_MILLIS = 300_000; // 5 minutes
    static final int DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS = 3_600_000; // 1 hour
    static final int MIN_BACKGROUND_POLLING_INTERVAL_MILLIS = 900_000; // 15 minutes
    static final int MIN_POLLING_INTERVAL_MILLIS = 300_000; // 5 minutes

    private final Map<String, String> mobileKeys;

    private final Uri baseUri;
    private final Uri eventsUri;
    private final Uri streamUri;

    private final int eventsCapacity;
    private final int eventsFlushIntervalMillis;
    private final int connectionTimeoutMillis;
    private final int pollingIntervalMillis;
    private final int backgroundPollingIntervalMillis;

    private final boolean stream;
    private final boolean offline;
    private final boolean disableBackgroundUpdating;
    private final boolean useReport;

    private final boolean allAttributesPrivate;
    private final Set<String> privateAttributeNames;

    private final Gson filteredEventGson;

    private final boolean inlineUsersInEvents;

    private final boolean evaluationReasons;

    LDConfig(Map<String, String> mobileKeys,
                    Uri baseUri,
                    Uri eventsUri,
                    Uri streamUri,
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
                    Set<String> privateAttributeNames,
                    boolean inlineUsersInEvents,
                    boolean evaluationReasons) {

        this.mobileKeys = mobileKeys;
        this.baseUri = baseUri;
        this.eventsUri = eventsUri;
        this.streamUri = streamUri;
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
        this.privateAttributeNames = privateAttributeNames;
        this.inlineUsersInEvents = inlineUsersInEvents;
        this.evaluationReasons = evaluationReasons;

        this.filteredEventGson = new GsonBuilder()
                .registerTypeAdapter(LDUser.class, new LDUser.LDUserPrivateAttributesTypeAdapter(this))
                .excludeFieldsWithoutExposeAnnotation().create();

    }

    Request.Builder getRequestBuilderFor(String environment) {
        if (environment == null)
            throw new IllegalArgumentException("null is not a valid environment");

        String key = mobileKeys.get(environment);
        if (key == null)
            throw new IllegalArgumentException("No environment by that name.");

        return new Request.Builder()
                .addHeader("Authorization", LDConfig.AUTH_SCHEME + key)
                .addHeader("User-Agent", USER_AGENT_HEADER_VALUE);
    }

    public String getMobileKey() {
        return mobileKeys.get(primaryEnvironmentName);
    }

    public Map<String, String> getMobileKeys() {
        return mobileKeys;
    }

    public Uri getBaseUri() {
        return baseUri;
    }

    public Uri getEventsUri() {
        return eventsUri;
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

    public Uri getStreamUri() {
        return streamUri;
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

    public Set<String> getPrivateAttributeNames() {
        return Collections.unmodifiableSet(privateAttributeNames);
    }

    public Gson getFilteredEventGson() {
        return filteredEventGson;
    }

    public boolean inlineUsersInEvents() {
        return inlineUsersInEvents;
    }

    public boolean isEvaluationReasons() {
        return evaluationReasons;
    }

    public static class Builder {
        private String mobileKey;
        private Map<String, String> secondaryMobileKeys;

        private Uri baseUri = DEFAULT_BASE_URI;
        private Uri eventsUri = DEFAULT_EVENTS_URI;
        private Uri streamUri = DEFAULT_STREAM_URI;

        private int eventsCapacity = DEFAULT_EVENTS_CAPACITY;
        private int eventsFlushIntervalMillis = 0;
        private int connectionTimeoutMillis = DEFAULT_CONNECTION_TIMEOUT_MILLIS;
        private int pollingIntervalMillis = DEFAULT_POLLING_INTERVAL_MILLIS;
        private int backgroundPollingIntervalMillis = DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS;

        private boolean offline = false;
        private boolean stream = true;
        private boolean disableBackgroundUpdating = false;
        private boolean useReport = false;

        private boolean allAttributesPrivate = false;
        private Set<String> privateAttributeNames = new HashSet<>();

        private boolean inlineUsersInEvents = false;
        private boolean evaluationReasons = false;

        /**
         * Specifies that user attributes (other than the key) should be hidden from LaunchDarkly.
         * If this is set, all user attribute values will be private, not just the attributes
         * specified in {@link #setPrivateAttributeNames(Set)}.
         *
         * @return the builder
         */
        public Builder allAttributesPrivate() {
            this.allAttributesPrivate = true;
            return this;
        }

        /**
         * Marks a set of attributes private. Any users sent to LaunchDarkly with this configuration
         * active will have attributes with these names removed.
         *
         * This can also be specified on a per-user basis with {@link LDUser.Builder} methods like
         * {@link LDUser.Builder#privateName(String)}.
         *
         * @param privateAttributeNames a set of names that will be removed from user data sent to LaunchDarkly
         * @return the builder
         */
        public Builder setPrivateAttributeNames(Set<String> privateAttributeNames) {
            this.privateAttributeNames = Collections.unmodifiableSet(privateAttributeNames);
            return this;
        }

        /**
         * Sets the key for authenticating with LaunchDarkly. This is required unless you're using the client in offline mode.
         *
         * @param mobileKey Get this from the LaunchDarkly web app under Team Settings.
         * @return the builder
         */
        public LDConfig.Builder setMobileKey(String mobileKey) {
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
        public LDConfig.Builder setSecondaryMobileKeys(Map<String, String> secondaryMobileKeys) {
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
         * Sets the flag for choosing the REPORT api call.  The default is GET.
         * Do not use unless advised by LaunchDarkly.
         *
         * @param useReport true if HTTP requests should use the REPORT verb
         * @return the builder
         */
        public LDConfig.Builder setUseReport(boolean useReport) {
            this.useReport = useReport;
            return this;
        }

        /**
         * Set the base URI for connecting to LaunchDarkly. You probably don't need to set this unless instructed by LaunchDarkly.
         *
         * @param baseUri the URI of the main LaunchDarkly service
         * @return the builder
         */
        public LDConfig.Builder setBaseUri(Uri baseUri) {
            this.baseUri = baseUri;
            return this;
        }

        /**
         * Set the events URI for sending analytics to LaunchDarkly. You probably don't need to set this unless instructed by LaunchDarkly.
         *
         * @param eventsUri the URI of the LaunchDarkly analytics event service
         * @return the builder
         */
        public LDConfig.Builder setEventsUri(Uri eventsUri) {
            this.eventsUri = eventsUri;
            return this;
        }

        /**
         * Set the stream URI for connecting to the flag update stream. You probably don't need to set this unless instructed by LaunchDarkly.
         *
         * @param streamUri the URI of the LaunchDarkly streaming service
         * @return the builder
         */
        public LDConfig.Builder setStreamUri(Uri streamUri) {
            this.streamUri = streamUri;
            return this;
        }

        /**
         * Set the capacity of the event buffer. The client buffers up to this many events in memory before flushing.
         * If the capacity is exceeded before the buffer is flushed, events will be discarded. Increasing the capacity
         * means that events are less likely to be discarded, at the cost of consuming more memory.
         * <p>
         * The default value is {@value LDConfig#DEFAULT_EVENTS_CAPACITY}.
         *
         * @param eventsCapacity the capacity of the event buffer
         * @return the builder
         * @see #setEventsFlushIntervalMillis(int)
         */
        public LDConfig.Builder setEventsCapacity(int eventsCapacity) {
            this.eventsCapacity = eventsCapacity;
            return this;
        }

        /**
         * Sets the maximum amount of time to wait in between sending analytics events to LaunchDarkly.
         * <p>
         * The default value is {@value LDConfig#DEFAULT_FLUSH_INTERVAL_MILLIS}.
         *
         * @param eventsFlushIntervalMillis the interval between event flushes, in milliseconds
         * @return the builder
         * @see #setEventsCapacity(int)
         */
        public LDConfig.Builder setEventsFlushIntervalMillis(int eventsFlushIntervalMillis) {
            this.eventsFlushIntervalMillis = eventsFlushIntervalMillis;
            return this;
        }


        /**
         * Sets the timeout when connecting to LaunchDarkly.
         * <p>
         * The default value is {@value LDConfig#DEFAULT_CONNECTION_TIMEOUT_MILLIS}.
         *
         * @param connectionTimeoutMillis the connection timeout, in milliseconds
         * @return the builder
         */
        public LDConfig.Builder setConnectionTimeoutMillis(int connectionTimeoutMillis) {
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
        public LDConfig.Builder setStream(boolean enabled) {
            this.stream = enabled;
            return this;
        }

        /**
         * Sets the interval in between feature flag updates, when streaming mode is disabled.
         * This is ignored unless {@link #setStream(boolean)} is set to {@code true}. When set, it
         * will also change the default value for {@link #setEventsFlushIntervalMillis(int)} to the
         * same value.
         * <p>
         * The default value is {@link LDConfig#DEFAULT_POLLING_INTERVAL_MILLIS}.
         *
         * @param pollingIntervalMillis the feature flag polling interval, in milliseconds
         * @return the builder
         */
        public LDConfig.Builder setPollingIntervalMillis(int pollingIntervalMillis) {
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
         */
        public LDConfig.Builder setBackgroundPollingIntervalMillis(int backgroundPollingIntervalMillis) {
            this.backgroundPollingIntervalMillis = backgroundPollingIntervalMillis;
            return this;
        }

        /**
         * Sets whether feature flag updates should be disabled when your app is in the background.
         * <p>
         * The default value is false (flag updates <i>will</i> be done in the background).
         *
         * @param disableBackgroundUpdating true if the client should skip updating flags when in the background
         */
        public LDConfig.Builder setDisableBackgroundUpdating(boolean disableBackgroundUpdating) {
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
        public LDConfig.Builder setOffline(boolean offline) {
            this.offline = offline;
            return this;
        }

        /**
         * If enabled, events to the server will be created containing the entire User object.
         * If disabled, events to the server will be created without the entire User object, including only the user key instead;
         * the rest of the user properties will still be included in Identify events.
         * <p>
         * Defaults to false in order to reduce network bandwidth.
         *
         * @param inlineUsersInEvents true if all user properties should be included in events
         * @return the builder
         */
        public LDConfig.Builder setInlineUsersInEvents(boolean inlineUsersInEvents) {
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
        public LDConfig.Builder setEvaluationReasons(boolean evaluationReasons) {
            this.evaluationReasons = evaluationReasons;
            return this;
        }

        /**
         * Returns the configured {@link LDConfig} object.
         * @return the configuration
         */
        public LDConfig build() {
            if (!stream) {
                if (pollingIntervalMillis < MIN_POLLING_INTERVAL_MILLIS) {
                    Timber.w("setPollingIntervalMillis: %s was set below the allowed minimum of: %s. Ignoring and using minimum value.", pollingIntervalMillis, MIN_POLLING_INTERVAL_MILLIS);
                    pollingIntervalMillis = MIN_POLLING_INTERVAL_MILLIS;
                }

                if (!disableBackgroundUpdating && backgroundPollingIntervalMillis < pollingIntervalMillis) {
                    Timber.w("BackgroundPollingIntervalMillis: %s was set below the foreground polling interval: %s. Ignoring and using minimum value for background polling.", backgroundPollingIntervalMillis, pollingIntervalMillis);
                    backgroundPollingIntervalMillis = MIN_BACKGROUND_POLLING_INTERVAL_MILLIS;
                }

                if (eventsFlushIntervalMillis == 0) {
                    eventsFlushIntervalMillis = pollingIntervalMillis;
                    Timber.d("Streaming is disabled, so we're setting the events flush interval to the polling interval value: %s", pollingIntervalMillis);
                }
            }

            if (!disableBackgroundUpdating) {
                if (backgroundPollingIntervalMillis < MIN_BACKGROUND_POLLING_INTERVAL_MILLIS) {
                    Timber.w("BackgroundPollingIntervalMillis: %s was set below the minimum allowed: %s. Ignoring and using minimum value.", backgroundPollingIntervalMillis, MIN_BACKGROUND_POLLING_INTERVAL_MILLIS);
                    backgroundPollingIntervalMillis = MIN_BACKGROUND_POLLING_INTERVAL_MILLIS;
                }
            }

            if (eventsFlushIntervalMillis == 0) {
                eventsFlushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS;
            }

            PollingUpdater.backgroundPollingIntervalMillis = backgroundPollingIntervalMillis;

            HashMap<String, String> mobileKeys;
            if (secondaryMobileKeys == null) {
                mobileKeys = new HashMap<>();
            }
            else {
                mobileKeys = new HashMap<>(secondaryMobileKeys);
            }
            mobileKeys.put(primaryEnvironmentName, mobileKey);

            return new LDConfig(
                    mobileKeys,
                    baseUri,
                    eventsUri,
                    streamUri,
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
                    privateAttributeNames,
                    inlineUsersInEvents,
                    evaluationReasons);
        }
    }
}
