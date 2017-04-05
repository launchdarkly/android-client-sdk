package com.launchdarkly.android;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import okhttp3.MediaType;
import okhttp3.Request;

public class LDConfig {
    private static final String TAG = "LDConfig";
    public static final String USER_AGENT_HEADER_VALUE = "AndroidClient/" + BuildConfig.VERSION_NAME;
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    private static final Uri DEFAULT_BASE_URI = Uri.parse("https://app.launchdarkly.com");
    private static final Uri DEFAULT_EVENTS_URI = Uri.parse("https://mobile.launchdarkly.com/mobile");
    private static final Uri DEFAULT_STREAM_URI = Uri.parse("https://stream.launchdarkly.com");
    private static final int DEFAULT_EVENTS_CAPACITY = 100;
    private static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 5000;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10000;
    private static final int DEFAULT_POLLING_INTERVAL_MILLIS = 300_000; // 1 hour
    private static final int MIN_POLLING_INTERVAL_MILLIS = 60_000; // 1 minute

    private final String mobileKey;

    private final Uri baseUri;
    private final Uri eventsUri;
    private final Uri streamUri;
    private final int eventsCapacity;
    private final int eventsFlushIntervalMillis;
    private final int connectionTimeoutMillis;
    private final boolean stream;

    private final boolean offline;
    private final int pollingIntervalMillis;


    public LDConfig(String mobileKey,
                    Uri baseUri,
                    Uri eventsUri,
                    Uri streamUri,
                    int eventsCapacity,
                    int eventsFlushIntervalMillis,
                    int connectionTimeoutMillis,
                    boolean offline,
                    boolean stream,
                    int pollingIntervalMillis) {
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

    public int getPollingIntervalMillis() {
        return pollingIntervalMillis;
    }

    public static class Builder {
        private String mobileKey;
        private Uri baseUri = DEFAULT_BASE_URI;
        private Uri eventsUri = DEFAULT_EVENTS_URI;
        private Uri streamUri = DEFAULT_STREAM_URI;
        private int eventsCapacity = DEFAULT_EVENTS_CAPACITY;
        private int eventsFlushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS;
        private int connectionTimeoutMillis = DEFAULT_CONNECTION_TIMEOUT_MILLIS;
        private boolean offline = false;
        private boolean stream = true;
        private int pollingIntervalMillis = DEFAULT_POLLING_INTERVAL_MILLIS;

        /**
         * Sets the key for authenticating with LaunchDarkly. This is required unless you're using the client in offline mode.
         * @param mobileKey Get this from the LaunchDarkly web app under Team Settings.
         * @return
         */
        public LDConfig.Builder setMobileKey(String mobileKey) {
            this.mobileKey = mobileKey;
            return this;
        }

        /**
         * Set the base uri for connecting to LaunchDarkly. You probably don't need to set this unless instructed by LaunchDarkly.
         * @param baseUri
         * @return
         */
        public LDConfig.Builder setBaseUri(Uri baseUri) {
            this.baseUri = baseUri;
            return this;
        }

        /**
         * Set the events uri for sending analytics to LaunchDarkly. You probably don't need to set this unless instructed by LaunchDarkly.
         * @param eventsUri
         * @return
         */
        public LDConfig.Builder setEventsUri(Uri eventsUri) {
            this.eventsUri = eventsUri;
            return this;
        }

        /**
         * Set the stream uri for connecting to the flag update stream. You probably don't need to set this unless instructed by LaunchDarkly.
         * @param streamUri
         * @return
         */
        public LDConfig.Builder setStreamUri(Uri streamUri) {
            this.streamUri = streamUri;
            return this;
        }

        /**
         * Sets the max number of events to queue before sending them to LaunchDarkly. Default: {@value LDConfig#DEFAULT_EVENTS_CAPACITY}
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
         * @param eventsFlushIntervalMillis
         * @return
         */
        public LDConfig.Builder setEventsFlushIntervalMillis(int eventsFlushIntervalMillis) {
            this.eventsFlushIntervalMillis = eventsFlushIntervalMillis;
            return this;
        }


        /**
         * Sets the timeout when connecting to LaunchDarkly. Default: {@value LDConfig#DEFAULT_CONNECTION_TIMEOUT_MILLIS}
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
         * @param pollingIntervalMillis
         * @return
         */
        public LDConfig.Builder setPollingIntervalMillis(int pollingIntervalMillis) {
            if (pollingIntervalMillis >= MIN_POLLING_INTERVAL_MILLIS) {
                Log.d(TAG, "Polling interval millis was specified: " + pollingIntervalMillis + ", so we're setting events flush interval millis to the same value.");
                this.pollingIntervalMillis = pollingIntervalMillis;
                this.eventsFlushIntervalMillis = pollingIntervalMillis;
            } else {
                Log.w(TAG, "setPollingIntervalMillis() was set to: " + pollingIntervalMillis
                        +" which is below the allowed minimum of: "+ MIN_POLLING_INTERVAL_MILLIS + ". Using minimum value.");
                this.pollingIntervalMillis = MIN_POLLING_INTERVAL_MILLIS;
            }
            return this;
        }

        /**
         * Disables all network calls from the LaunchDarkly Client. Once the client has been created,
         * use the {@link LDClient#setOffline()} method. Default: false
         *
         * @param offline
         * @return
         */
        public LDConfig.Builder setOffline(boolean offline) {
            this.offline = offline;
            return this;
        }

        public LDConfig build() {
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
                    pollingIntervalMillis);
        }
    }
}
