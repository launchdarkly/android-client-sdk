package com.launchdarkly.android;

import android.support.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.HashSet;

import okhttp3.Request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class LDConfigTest {

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Test
    public void testBuilderDefaults() {
        LDConfig config = new LDConfig.Builder().build();
        assertTrue(config.isStream());
        assertFalse(config.isOffline());

        //noinspection deprecation
        assertEquals(LDConfig.DEFAULT_POLL_URI, config.getBaseUri());
        assertEquals(LDConfig.DEFAULT_POLL_URI, config.getPollUri());
        assertEquals(LDConfig.DEFAULT_EVENTS_URI, config.getEventsUri());
        assertEquals(LDConfig.DEFAULT_STREAM_URI, config.getStreamUri());

        assertEquals(LDConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS, config.getConnectionTimeoutMillis());
        assertEquals(LDConfig.DEFAULT_EVENTS_CAPACITY, config.getEventsCapacity());
        assertEquals(LDConfig.DEFAULT_FLUSH_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS, config.getDiagnosticRecordingIntervalMillis());

        assertEquals(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS, config.getBackgroundPollingIntervalMillis());
        assertFalse(config.isDisableBackgroundPolling());

        assertNull(config.getMobileKey());
        assertFalse(config.inlineUsersInEvents());
        assertFalse(config.isEvaluationReasons());
        assertFalse(config.getDiagnosticOptOut());

        assertNull(config.getWrapperName());
        assertNull(config.getWrapperVersion());
        assertNull(config.getAdditionalHeaders());
        assertFalse(config.isAutoAliasingOptOut());
    }


    @Test
    public void testBuilderStreamDisabled() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS, config.getBackgroundPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void testBuilderStreamDisabledCustomIntervals() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setPollingIntervalMillis(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1)
                .setBackgroundPollingIntervalMillis(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS + 2)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS + 2, config.getBackgroundPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void testBuilderStreamDisabledBackgroundUpdatingDisabled() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setDisableBackgroundUpdating(true)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertTrue(config.isDisableBackgroundPolling());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void testBuilderStreamDisabledPollingIntervalBelowMinimum() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setPollingIntervalMillis(LDConfig.MIN_POLLING_INTERVAL_MILLIS - 1)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertFalse(config.isDisableBackgroundPolling());
        assertEquals(LDConfig.MIN_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS, config.getBackgroundPollingIntervalMillis());
        assertEquals(LDConfig.MIN_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void testBuilderStreamDisabledBackgroundPollingIntervalBelowMinimum() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setBackgroundPollingIntervalMillis(LDConfig.MIN_BACKGROUND_POLLING_INTERVAL_MILLIS - 1)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertFalse(config.isDisableBackgroundPolling());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.MIN_BACKGROUND_POLLING_INTERVAL_MILLIS, config.getBackgroundPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void testBuilderDiagnosticRecordingInterval() {
        LDConfig config = new LDConfig.Builder()
                .setDiagnosticRecordingIntervalMillis(LDConfig.DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS + 1)
                .build();

        assertFalse(config.getDiagnosticOptOut());
        assertEquals(LDConfig.DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS + 1, config.getDiagnosticRecordingIntervalMillis());
    }

    @Test
    public void testBuilderDiagnosticRecordingIntervalBelowMinimum() {
        LDConfig config = new LDConfig.Builder()
                .setDiagnosticRecordingIntervalMillis(LDConfig.MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS - 1)
                .build();

        assertFalse(config.getDiagnosticOptOut());
        assertEquals(LDConfig.MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS, config.getDiagnosticRecordingIntervalMillis());
    }

    @Test
    public void testBuilderUseReportDefaultGet() {
        LDConfig config = new LDConfig.Builder()
                .build();

        assertFalse(config.isUseReport());
    }

    @Test
    public void testBuilderUseReportSetToGet() {
        LDConfig config = new LDConfig.Builder()
                .setUseReport(false)
                .build();

        assertFalse(config.isUseReport());
    }

    @Test
    public void testBuilderUseReportSetToReport() {
        LDConfig config = new LDConfig.Builder()
                .setUseReport(true)
                .build();

        assertTrue(config.isUseReport());
    }

    @Test
    public void testBuilderAllAttributesPrivate() {
        LDConfig config = new LDConfig.Builder()
                .build();

        assertFalse(config.allAttributesPrivate());

        config = new LDConfig.Builder()
                .allAttributesPrivate()
                .build();

        assertTrue(config.allAttributesPrivate());
    }

    @Test
    public void testBuilderPrivateAttributesList() {
        LDConfig config = new LDConfig.Builder()
                .build();

        assertEquals(config.getPrivateAttributeNames().size(), 0);

        config = new LDConfig.Builder()
                .setPrivateAttributeNames(new HashSet<String>() {
                    {
                        add("email");
                        add("name");
                    }
                })
                .build();

        assertEquals(config.getPrivateAttributeNames().size(), 2);
    }

    @Test
    public void testBuilderEvaluationReasons() {
        LDConfig config = new LDConfig.Builder().setEvaluationReasons(true).build();

        assertTrue(config.isEvaluationReasons());
    }

    @Test
    public void testBuilderDiagnosticOptOut() {
        LDConfig config = new LDConfig.Builder().setDiagnosticOptOut(true).build();

        assertTrue(config.getDiagnosticOptOut());
    }

    @Test
    public void testBuilderWrapperName() {
        LDConfig config = new LDConfig.Builder().setWrapperName("Scala").build();
        assertEquals("Scala", config.getWrapperName());
    }

    @Test
    public void testBuilderWrapperVersion() {
        LDConfig config = new LDConfig.Builder().setWrapperVersion("0.1.0").build();
        assertEquals("0.1.0", config.getWrapperVersion());
    }

    @Test
    public void testBuilderMaxCachedUsers() {
        LDConfig config = new LDConfig.Builder().setMaxCachedUsers(0).build();
        assertEquals(0, config.getMaxCachedUsers());
        config = new LDConfig.Builder().setMaxCachedUsers(10).build();
        assertEquals(10, config.getMaxCachedUsers());
        config = new LDConfig.Builder().setMaxCachedUsers(-1).build();
        assertEquals(-1, config.getMaxCachedUsers());
    }

    @Test
    public void canSetAdditionalHeaders() {
        HashMap<String, String> additionalHeaders = new HashMap<>();
        additionalHeaders.put("Proxy-Authorization", "token");
        additionalHeaders.put("Authorization", "foo");
        LDConfig config = new LDConfig.Builder().setAdditionalHeaders(additionalHeaders).build();
        assertEquals(2, config.getAdditionalHeaders().size());
        assertEquals("token", config.getAdditionalHeaders().get("Proxy-Authorization"));
        assertEquals("foo", config.getAdditionalHeaders().get("Authorization"));
    }

    @Test
    public void buildRequestWithAdditionalHeadersNull() {
        LDConfig config = new LDConfig.Builder().build();
        Request.Builder requestBuilder = new Request.Builder()
                .url("http://example.com")
                .header("Authorization", "test-key");
        Request request = config.buildRequestWithAdditionalHeaders(requestBuilder);
        assertEquals(1, request.headers().size());
        assertEquals("test-key", request.header("Authorization"));
    }

    @Test
    public void buildRequestWithAdditionalHeaders() {
        HashMap<String, String> additionalHeaders = new HashMap<>();
        additionalHeaders.put("Proxy-Authorization", "token");
        additionalHeaders.put("Authorization", "foo");
        LDConfig config = new LDConfig.Builder().setAdditionalHeaders(additionalHeaders).build();
        Request.Builder requestBuilder = new Request.Builder()
                .url("http://example.com")
                .header("Authorization", "test-key");
        Request request = config.buildRequestWithAdditionalHeaders(requestBuilder);
        assertEquals(2, request.headers().size());
        assertEquals("token", request.header("Proxy-Authorization"));
        assertEquals("foo", request.header("Authorization"));
    }

    @Test
    public void buildWithAutoAliasingOptOut() {
        LDConfig config = new LDConfig.Builder().setAutoAliasingOptOut(true).build();
        assertTrue(config.isAutoAliasingOptOut());
    }
}