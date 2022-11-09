package com.launchdarkly.sdk.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonElement;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.UserAttribute;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Headers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("deprecation")
@RunWith(AndroidJUnit4.class)
public class LDConfigTest {

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Test
    public void testBuilderDefaults() {
        LDConfig config = new LDConfig.Builder().build();
        assertTrue(config.isStream());
        assertFalse(config.isOffline());

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
        assertFalse(config.isAutoAliasingOptOut());
    }


    @Test
    public void testBuilderStreamDisabled() {
        LDConfig config = new LDConfig.Builder()
                .stream(false)
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
                .stream(false)
                .pollingIntervalMillis(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1)
                .backgroundPollingIntervalMillis(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS + 2)
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
                .stream(false)
                .disableBackgroundUpdating(true)
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
                .stream(false)
                .pollingIntervalMillis(LDConfig.MIN_POLLING_INTERVAL_MILLIS - 1)
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
                .stream(false)
                .backgroundPollingIntervalMillis(LDConfig.MIN_BACKGROUND_POLLING_INTERVAL_MILLIS - 1)
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
                .diagnosticRecordingIntervalMillis(LDConfig.DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS + 1)
                .build();

        assertFalse(config.getDiagnosticOptOut());
        assertEquals(LDConfig.DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS + 1, config.getDiagnosticRecordingIntervalMillis());
    }

    @Test
    public void testBuilderDiagnosticRecordingIntervalBelowMinimum() {
        LDConfig config = new LDConfig.Builder()
                .diagnosticRecordingIntervalMillis(LDConfig.MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS - 1)
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
                .useReport(false)
                .build();

        assertFalse(config.isUseReport());
    }

    @Test
    public void testBuilderUseReportSetToReport() {
        LDConfig config = new LDConfig.Builder()
                .useReport(true)
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

        assertEquals(config.getPrivateAttributes().size(), 0);

        config = new LDConfig.Builder()
                .privateAttributes(UserAttribute.forName("email"), UserAttribute.forName("name"))
                .build();

        assertEquals(config.getPrivateAttributes().size(), 2);
    }

    @Test
    public void testBuilderEvaluationReasons() {
        LDConfig config = new LDConfig.Builder().evaluationReasons(true).build();

        assertTrue(config.isEvaluationReasons());
    }

    @Test
    public void testBuilderDiagnosticOptOut() {
        LDConfig config = new LDConfig.Builder().diagnosticOptOut(true).build();

        assertTrue(config.getDiagnosticOptOut());
    }

    @Test
    public void testBuilderWrapperName() {
        LDConfig config = new LDConfig.Builder().wrapperName("Scala").build();
        assertEquals("Scala", config.getWrapperName());
    }

    @Test
    public void testBuilderWrapperVersion() {
        LDConfig config = new LDConfig.Builder().wrapperVersion("0.1.0").build();
        assertEquals("0.1.0", config.getWrapperVersion());
    }

    @Test
    public void testBuilderMaxCachedUsers() {
        LDConfig config = new LDConfig.Builder().maxCachedUsers(0).build();
        assertEquals(0, config.getMaxCachedUsers());
        config = new LDConfig.Builder().maxCachedUsers(10).build();
        assertEquals(10, config.getMaxCachedUsers());
        config = new LDConfig.Builder().maxCachedUsers(-1).build();
        assertEquals(-1, config.getMaxCachedUsers());
    }

    Map<String, String> headersToMap(Headers headers) {
        Map<String, List<String>> multimap = headers.toMultimap();
        HashMap<String, String> collapsed = new HashMap<>();
        for (Map.Entry<String, List<String>> entry: multimap.entrySet()) {
            if (entry.getValue().size() != 1) {
                fail("Expected 1-to-1 mapping of headers");
            }
            collapsed.put(entry.getKey(), entry.getValue().get(0));
        }
        return collapsed;
    }

    @Test
    public void headersForEnvironment() {
        LDConfig config = new LDConfig.Builder().mobileKey("test-key").build();
        Map<String, String> headers = headersToMap(config.headersForEnvironment(LDConfig.primaryEnvironmentName, null));
        assertEquals(2, headers.size());
        assertEquals(LDConfig.USER_AGENT_HEADER_VALUE, headers.get("user-agent"));
        assertEquals("api_key test-key", headers.get("authorization"));
        // Additional headers extend/replace defaults
        HashMap<String, String> additional = new HashMap<>();
        additional.put("Authorization", "other-key");
        additional.put("Proxy-Authorization", "token");
        headers = headersToMap(config.headersForEnvironment(LDConfig.primaryEnvironmentName, additional));
        assertEquals(3, headers.size());
        assertEquals(LDConfig.USER_AGENT_HEADER_VALUE, headers.get("user-agent"));
        assertEquals("other-key", headers.get("authorization"));
        assertEquals("token", headers.get("proxy-authorization"));
        // Also should not modify the given additional headers
        assertEquals(2, additional.size());
        assertEquals("other-key", additional.get("Authorization"));
        assertEquals("token", additional.get("Proxy-Authorization"));
    }

    @Test
    public void headersForEnvironmentWithTransform() {
        HashMap<String, String> expected = new HashMap<>();
        LDConfig config = new LDConfig.Builder().mobileKey("test-key")
                .headerTransform(headers -> {
                    assertEquals(expected, headers);
                    headers.remove("User-Agent");
                    headers.put("Authorization", headers.get("Authorization") + ", more");
                    headers.put("New", "value");
                })
                .build();

        expected.put("User-Agent", LDConfig.USER_AGENT_HEADER_VALUE);
        expected.put("Authorization", "api_key test-key");
        Map<String, String> headers = headersToMap(config.headersForEnvironment(LDConfig.primaryEnvironmentName, null));
        assertEquals(2, headers.size());
        assertEquals("api_key test-key, more", headers.get("authorization"));
        assertEquals("value", headers.get("new"));
        // Additional headers extend/replace defaults
        HashMap<String, String> additional = new HashMap<>();
        additional.put("Authorization", "other-key");
        additional.put("Proxy-Authorization", "token");
        expected.putAll(additional);
        headers = headersToMap(config.headersForEnvironment(LDConfig.primaryEnvironmentName, additional));
        assertEquals(3, headers.size());
        assertEquals("other-key, more", headers.get("authorization"));
        assertEquals("token", headers.get("proxy-authorization"));
        assertEquals("value", headers.get("new"));
        // Also should not modify the given additional headers
        assertEquals(2, additional.size());
        assertEquals("other-key", additional.get("Authorization"));
        assertEquals("token", additional.get("Proxy-Authorization"));
    }

    @Test
    public void buildWithAutoAliasingOptOut() {
        LDConfig config = new LDConfig.Builder().autoAliasingOptOut(true).build();
        assertTrue(config.isAutoAliasingOptOut());
    }

    @Test
    public void keyShouldNeverBeRemoved() {
        // even with all attributes being private the key should always be retained
        LDConfig config = new LDConfig.Builder()
            .allAttributesPrivate()
            .build();

        LDUser user = new LDUser.Builder("myUserKey").email("weShouldNotFindThis@test.com").build();

        JsonElement elem = config.getFilteredEventGson().toJsonTree(user).getAsJsonObject();

        assertNotNull(elem);

        assertTrue(elem.toString().contains("myUserKey"));
        assertFalse(elem.toString().contains("weShouldNotFindThis@test.com"));
    }
}
