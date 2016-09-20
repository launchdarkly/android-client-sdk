package com.launchdarkly.android;

import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import okhttp3.MediaType;
import okhttp3.Request;

public class LDConfig {
    private static final String TAG = "LDConfig";
    public static final String USER_AGENT_HEADER_VALUE = "AndroidClient/" + BuildConfig.VERSION_NAME;
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    private static final Uri DEFAULT_BASE_Uri = Uri.parse("https://app.launchdarkly.com");
    private static final Uri DEFAULT_EVENTS_Uri = Uri.parse("https://mobile.launchdarkly.com/mobile");
    private static final Uri DEFAULT_STREAM_Uri = Uri.parse("https://stream.launchdarkly.com");
    private static final int DEFAULT_EVENTS_CAPACITY = 100;
    private static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 5000;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10000;

    private final String mobileKey;

    private final Uri baseUri;
    private final Uri eventsUri;
    private final Uri streamUri;
    private final int eventsCapacity;
    private final int eventsFlushIntervalMillis;
    private final int connectionTimeoutMillis;

    private final boolean offline;


    public LDConfig(String mobileKey,
                    Uri baseUri,
                    Uri eventsUri,
                    Uri streamUri,
                    int eventsCapacity,
                    int eventsFlushIntervalMillis,
                    int connectionTimeoutMillis,
                    boolean offline) {
        this.mobileKey = mobileKey;
        this.baseUri = baseUri;
        this.eventsUri = eventsUri;
        this.streamUri = streamUri;
        this.eventsCapacity = eventsCapacity;
        this.eventsFlushIntervalMillis = eventsFlushIntervalMillis;
        this.connectionTimeoutMillis = connectionTimeoutMillis;
        this.offline = offline;
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

    public static class Builder {
        private String mobileKey;
        private Uri baseUri = DEFAULT_BASE_Uri;
        private Uri eventsUri = DEFAULT_EVENTS_Uri;
        private Uri streamUri = DEFAULT_STREAM_Uri;
        private int eventsCapacity = DEFAULT_EVENTS_CAPACITY;
        private int eventsFlushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS;
        private int connectionTimeoutMillis = DEFAULT_CONNECTION_TIMEOUT_MILLIS;
        private boolean offline = false;

        public LDConfig.Builder setMobileKey(String mobileKey) {
            this.mobileKey = mobileKey;
            return this;
        }

        public LDConfig.Builder setBaseUri(Uri baseUri) {
            this.baseUri = baseUri;
            return this;
        }

        public LDConfig.Builder setEventsUri(Uri eventsUri) {
            this.eventsUri = eventsUri;
            return this;
        }

        public LDConfig.Builder setStreamUri(Uri streamUri) {
            this.streamUri = streamUri;
            return this;
        }

        public LDConfig.Builder setEventsCapacity(int eventsCapacity) {
            this.eventsCapacity = eventsCapacity;
            return this;
        }

        public LDConfig.Builder setEventsFlushIntervalMillis(int eventsFlushIntervalMillis) {
            this.eventsFlushIntervalMillis = eventsFlushIntervalMillis;
            return this;
        }

        public LDConfig.Builder setConnectionTimeoutMillis(int connectionTimeoutMillis) {
            this.connectionTimeoutMillis = connectionTimeoutMillis;
            return this;
        }

        public LDConfig.Builder setOffline(boolean offline) {
            this.offline = offline;
            return this;
        }

        public LDConfig build() {
            return new LDConfig(mobileKey, baseUri, eventsUri, streamUri, eventsCapacity, eventsFlushIntervalMillis, connectionTimeoutMillis, offline);
        }
    }
}
