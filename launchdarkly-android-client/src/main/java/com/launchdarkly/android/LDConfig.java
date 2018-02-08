package com.launchdarkly.android;

import android.net.Uri;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.Request;

public class LDConfig {

    private static final String TAG = "LDConfig";
    static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";
    static final String USER_AGENT_HEADER_VALUE = "AndroidClient/" + BuildConfig.VERSION_NAME;
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    static final Uri DEFAULT_BASE_URI = Uri.parse("https://app.launchdarkly.com");
    static final Uri DEFAULT_EVENTS_URI = Uri.parse("https://mobile.launchdarkly.com/mobile");
    static final Uri DEFAULT_STREAM_URI = Uri.parse("https://clientstream.launchdarkly.com");

    static final int DEFAULT_EVENTS_CAPACITY = 100;
    static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 30000;
    static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10000;
    static final int DEFAULT_POLLING_INTERVAL_MILLIS = 300_000; // 5 minutes
    static final int DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS = 3_600_000; // 1 hour
    static final int MIN_BACKGROUND_POLLING_INTERVAL_MILLIS = 900_000; // 15 minutes
    static final int MIN_POLLING_INTERVAL_MILLIS = 60_000; // 1 minute

    private final String mobileKey;

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

    public LDConfig(String mobileKey,
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
                    Set<String> privateAttributeNames) {

        this.mobileKey = mobileKey;
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

        this.filteredEventGson = new GsonBuilder()
                .registerTypeAdapter(LDUser.class, new LDUser.LDUserPrivateAttributesTypeAdapter(this))
                .excludeFieldsWithoutExposeAnnotation().create();

    }

    public Request.Builder getRequestBuilder() {
        return new Request.Builder()
                .addHeader("Authorization", mobileKey)
                .addHeader("User-Agent", USER_AGENT_HEADER_VALUE);
    }

    public String getMobileKey() {
        return mobileKey;
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

    public static class Builder {
        private String mobileKey;

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

        /**
         * Sets the flag for making all attributes private. The default is false.
         */
        public Builder allAttributesPrivate() {
            this.allAttributesPrivate = true;
            return this;
        }

        /**
         * Sets the name of private attributes.
         * Private attributes are not sent to LaunchDarkly.
         */
        public Builder setPrivateAttributeNames(Set<String> privateAttributeNames) {
            this.privateAttributeNames = Collections.unmodifiableSet(privateAttributeNames);
            return this;
        }

        /**
         * Sets the key for authenticating with LaunchDarkly. This is required unless you're using the client in offline mode.
         *
         * @param mobileKey Get this from the LaunchDarkly web app under Team Settings.
         * @return
         */
        public LDConfig.Builder setMobileKey(String mobileKey) {
            this.mobileKey = mobileKey;
            return this;
        }

        /**
         * Sets the flag for choosing the REPORT api call.  The default is GET.
         * Do not use unless advised by LaunchDarkly.
         */
        public LDConfig.Builder setUseReport(boolean useReport) {
            this.useReport = useReport;
            return this;
        }

        /**
         * Set the base uri for connecting to LaunchDarkly. You probably don't need to set this unless instructed by LaunchDarkly.
         *
         * @param baseUri
         * @return
         */
        public LDConfig.Builder setBaseUri(Uri baseUri) {
            this.baseUri = baseUri;
            return this;
        }

        /**
         * Set the events uri for sending analytics to LaunchDarkly. You probably don't need to set this unless instructed by LaunchDarkly.
         *
         * @param eventsUri
         * @return
         */
        public LDConfig.Builder setEventsUri(Uri eventsUri) {
            this.eventsUri = eventsUri;
            return this;
        }

        /**
         * Set the stream uri for connecting to the flag update stream. You probably don't need to set this unless instructed by LaunchDarkly.
         *
         * @param streamUri
         * @return
         */
        public LDConfig.Builder setStreamUri(Uri streamUri) {
            this.streamUri = streamUri;
            return this;
        }

        /**
         * Sets the max number of events to queue before sending them to LaunchDarkly. Default: {@value LDConfig#DEFAULT_EVENTS_CAPACITY}
         *
         * @param eventsCapacity
         * @return
         */
        public LDConfig.Builder setEventsCapacity(int eventsCapacity) {
            this.eventsCapacity = eventsCapacity;
            return this;
        }

        /**
         * Sets the maximum amount of time in milliseconds to wait in between sending analytics events to LaunchDarkly.
         * Default: {@value LDConfig#DEFAULT_FLUSH_INTERVAL_MILLIS}
         *
         * @param eventsFlushIntervalMillis
         * @return
         */
        public LDConfig.Builder setEventsFlushIntervalMillis(int eventsFlushIntervalMillis) {
            this.eventsFlushIntervalMillis = eventsFlushIntervalMillis;
            return this;
        }


        /**
         * Sets the timeout in milliseconds when connecting to LaunchDarkly. Default: {@value LDConfig#DEFAULT_CONNECTION_TIMEOUT_MILLIS}
         *
         * @param connectionTimeoutMillis
         * @return
         */
        public LDConfig.Builder setConnectionTimeoutMillis(int connectionTimeoutMillis) {
            this.connectionTimeoutMillis = connectionTimeoutMillis;
            return this;
        }


        /**
         * Enables or disables real-time streaming flag updates. Default: true. When set to false,
         * an efficient caching polling mechanism is used.
         *
         * @param enabled
         * @return
         */
        public LDConfig.Builder setStream(boolean enabled) {
            this.stream = enabled;
            return this;
        }

        /**
         * Only relevant when setStream(false) is called. Sets the interval between feature flag updates. Default: {@link LDConfig#DEFAULT_POLLING_INTERVAL_MILLIS}
         * Minimum value: {@link LDConfig#MIN_POLLING_INTERVAL_MILLIS}. When set, this will also set the eventsFlushIntervalMillis to the same value.
         *
         * @param pollingIntervalMillis
         * @return
         */
        public LDConfig.Builder setPollingIntervalMillis(int pollingIntervalMillis) {
            this.pollingIntervalMillis = pollingIntervalMillis;
            return this;
        }

        /**
         * Sets the interval in milliseconds that twe will poll for flag updates when your app is in the background. Default:
         * {@link LDConfig#DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS}
         *
         * @param backgroundPollingIntervalMillis
         */
        public LDConfig.Builder setBackgroundPollingIntervalMillis(int backgroundPollingIntervalMillis) {
            this.backgroundPollingIntervalMillis = backgroundPollingIntervalMillis;
            return this;
        }

        /**
         * Disables feature flag updates when your app is in the background. Default: false
         *
         * @param disableBackgroundUpdating
         */
        public LDConfig.Builder setDisableBackgroundUpdating(boolean disableBackgroundUpdating) {
            this.disableBackgroundUpdating = disableBackgroundUpdating;
            return this;
        }

        /**
         * Disables all network calls from the LaunchDarkly Client. Once the client has been created,
         * use the {@link LDClient#setOffline()} method to disable network calls. Default: false
         *
         * @param offline
         * @return
         */
        public LDConfig.Builder setOffline(boolean offline) {
            this.offline = offline;
            return this;
        }

        public LDConfig build() {
            if (!stream) {
                if (pollingIntervalMillis < MIN_POLLING_INTERVAL_MILLIS) {
                    Log.w(TAG, "setPollingIntervalMillis: " + pollingIntervalMillis
                            + " was set below the allowed minimum of: " + MIN_POLLING_INTERVAL_MILLIS + ". Ignoring and using minimum value.");
                    pollingIntervalMillis = MIN_POLLING_INTERVAL_MILLIS;
                }

                if (!disableBackgroundUpdating && backgroundPollingIntervalMillis < pollingIntervalMillis) {
                    Log.w(TAG, "BackgroundPollingIntervalMillis: " + backgroundPollingIntervalMillis +
                            " was set below the foreground polling interval: " + pollingIntervalMillis + ". Ignoring and using minimum value for background polling.");
                    backgroundPollingIntervalMillis = MIN_BACKGROUND_POLLING_INTERVAL_MILLIS;
                }

                if (eventsFlushIntervalMillis == 0) {
                    eventsFlushIntervalMillis = pollingIntervalMillis;
                    Log.d(TAG, "Streaming is disabled, so we're setting the events flush interval to the polling interval value: " + pollingIntervalMillis);
                }
            }

            if (!disableBackgroundUpdating) {
                if (backgroundPollingIntervalMillis < MIN_BACKGROUND_POLLING_INTERVAL_MILLIS) {
                    Log.w(TAG, "BackgroundPollingIntervalMillis: " + backgroundPollingIntervalMillis +
                            " was set below the minimum allowed: " + MIN_BACKGROUND_POLLING_INTERVAL_MILLIS + ". Ignoring and using minimum value.");
                    backgroundPollingIntervalMillis = MIN_BACKGROUND_POLLING_INTERVAL_MILLIS;
                }
            }

            if (eventsFlushIntervalMillis == 0) {
                eventsFlushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS;
            }

            PollingUpdater.backgroundPollingIntervalMillis = backgroundPollingIntervalMillis;

            return new LDConfig(
                    mobileKey,
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
                    privateAttributeNames);
        }
    }
}
