package com.launchdarkly.sdk.android;

import org.junit.Rule;
import org.junit.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Headers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.android.subsystems.ClientContext;

public class LDConfigTest {
    @Rule public LogCaptureRule logging = new LogCaptureRule();

    @Test
    public void testBuilderDefaults() {
        LDConfig config = new LDConfig.Builder().build();
        assertFalse(config.isOffline());

        assertFalse(config.isDisableBackgroundPolling());

        assertNull(config.getMobileKey());
        assertFalse(config.isEvaluationReasons());
        assertFalse(config.getDiagnosticOptOut());
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
    public void testBuilderMaxCachedUsers() {
        LDConfig config = new LDConfig.Builder().maxCachedContexts(0).build();
        assertEquals(0, config.getMaxCachedContexts());
        config = new LDConfig.Builder().maxCachedContexts(10).build();
        assertEquals(10, config.getMaxCachedContexts());
        config = new LDConfig.Builder().maxCachedContexts(-1).build();
        assertEquals(-1, config.getMaxCachedContexts());
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
        ClientContext clientContext = ClientContextImpl.fromConfig(config, "test-key", "",
                null, null, null, null);
        Map<String, String> headers = headersToMap(
                LDUtil.makeHttpProperties(clientContext).toHeadersBuilder().build()
        );
        assertEquals(2, headers.size());
        assertEquals(LDUtil.USER_AGENT_HEADER_VALUE, headers.get("user-agent"));
        assertEquals("api_key test-key", headers.get("authorization"));
    }

    @Test
    public void headersForEnvironmentWithTransform() {
        HashMap<String, String> expected = new HashMap<>();
        LDConfig config = new LDConfig.Builder().mobileKey("test-key")
                .http(Components.httpConfiguration().headerTransform(headers -> {
                    assertEquals(expected, headers);
                    headers.remove("User-Agent");
                    headers.put("Authorization", headers.get("Authorization") + ", more");
                    headers.put("New", "value");
                }))
                .build();
        ClientContext clientContext = ClientContextImpl.fromConfig(config, "test-key", "",
                null, null, null, null);

        expected.put("User-Agent", LDUtil.USER_AGENT_HEADER_VALUE);
        expected.put("Authorization", "api_key test-key");
        Map<String, String> headers = headersToMap(
                LDUtil.makeHttpProperties(clientContext).toHeadersBuilder().build()
        );
        assertEquals(2, headers.size());
        assertEquals("api_key test-key, more", headers.get("authorization"));
        assertEquals("value", headers.get("new"));
    }

    @Test
    public void serviceEndpointsDefault() {
        LDConfig config = new LDConfig.Builder().mobileKey("test-key").build();
        assertEquals(StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
                config.serviceEndpoints.getStreamingBaseUri());
        assertEquals(StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                config.serviceEndpoints.getPollingBaseUri());
        assertEquals(StandardEndpoints.DEFAULT_EVENTS_BASE_URI,
                config.serviceEndpoints.getEventsBaseUri());
    }

    @Test
    public void serviceEndpointsBuilderNullIsSameAsDefault() {
        LDConfig config = new LDConfig.Builder().mobileKey("test-key")
                .serviceEndpoints(
                        Components.serviceEndpoints().streaming("x")
                )
                .serviceEndpoints(null)
                .build();
        assertEquals(StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
                config.serviceEndpoints.getStreamingBaseUri());
        assertEquals(StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                config.serviceEndpoints.getPollingBaseUri());
        assertEquals(StandardEndpoints.DEFAULT_EVENTS_BASE_URI,
                config.serviceEndpoints.getEventsBaseUri());
    }

    @Test
    public void serviceEndpointsCustom() {
        LDConfig config = new LDConfig.Builder().mobileKey("test-key")
                .serviceEndpoints(
                        Components.serviceEndpoints().streaming("http://uri1")
                                .polling("http://uri2")
                                .events("http://uri3")
                )
                .build();
        assertEquals(URI.create("http://uri1"),
                config.serviceEndpoints.getStreamingBaseUri());
        assertEquals(URI.create("http://uri2"),
                config.serviceEndpoints.getPollingBaseUri());
        assertEquals(URI.create("http://uri3"),
                config.serviceEndpoints.getEventsBaseUri());
    }
}
