package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.easymock.EasyMock.createMock;

import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.integrations.Hook;
import com.launchdarkly.sdk.android.integrations.HooksConfigurationBuilder;
import com.launchdarkly.sdk.android.subsystems.ClientContext;

import org.junit.Rule;
import org.junit.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Headers;

public class LDConfigTest {
    @Rule public LogCaptureRule logging = new LogCaptureRule();

    @Test
    public void testBuilderDefaults() {
        LDConfig config = new LDConfig.Builder(LDConfig.Builder.AutoEnvAttributes.Disabled).build();
        assertFalse(config.isOffline());

        assertFalse(config.isDisableBackgroundPolling());

        assertNull(config.getMobileKey());
        assertFalse(config.isEvaluationReasons());
        assertFalse(config.getDiagnosticOptOut());

        assertEquals(0, config.hooks.getHooks().size());
    }

    @Test
    public void testBuilderEvaluationReasons() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).evaluationReasons(true).build();

        assertTrue(config.isEvaluationReasons());
    }

    @Test
    public void testBuilderDiagnosticOptOut() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).diagnosticOptOut(true).build();

        assertTrue(config.getDiagnosticOptOut());
    }

    @Test
    public void testBuilderMaxCachedContexts() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).maxCachedContexts(0).build();
        assertEquals(0, config.getMaxCachedContexts());
        config = new LDConfig.Builder(AutoEnvAttributes.Disabled).maxCachedContexts(10).build();
        assertEquals(10, config.getMaxCachedContexts());
        config = new LDConfig.Builder(AutoEnvAttributes.Disabled).maxCachedContexts(-1).build();
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
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).mobileKey("test-key").build();
        ClientContext clientContext = ClientContextImpl.fromConfig(config, "test-key", "",
                null, null, null, null, null, new EnvironmentReporterBuilder().build(), null);
        Map<String, String> headers = headersToMap(
                LDUtil.makeHttpProperties(clientContext).toHeadersBuilder().build()
        );
        assertEquals(3, headers.size());
        assertEquals(LDUtil.USER_AGENT_HEADER_VALUE, headers.get("user-agent"));
        assertEquals("api_key test-key", headers.get("authorization"));
    }

    @Test
    public void headersForEnvironmentWithTransform() {
        HashMap<String, String> expected = new HashMap<>();
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).mobileKey("test-key")
                .http(Components.httpConfiguration().headerTransform(headers -> {
                    assertEquals(expected, headers);
                    headers.remove("User-Agent");
                    headers.put("Authorization", headers.get("Authorization") + ", more");
                    headers.put("New", "value");
                }))
                .build();
        ClientContext clientContext = ClientContextImpl.fromConfig(config, "test-key", "",
                null, null, null, null, null, new EnvironmentReporterBuilder().build(), null);

        expected.put("User-Agent", LDUtil.USER_AGENT_HEADER_VALUE);
        expected.put("Authorization", "api_key test-key");
        expected.put("X-LaunchDarkly-Tags", "application-id/" + LDPackageConsts.SDK_NAME + " application-name/" + LDPackageConsts.SDK_NAME +
                " application-version/" + BuildConfig.VERSION_NAME + " application-version-name/" + BuildConfig.VERSION_NAME);
        Map<String, String> headers = headersToMap(
                LDUtil.makeHttpProperties(clientContext).toHeadersBuilder().build()
        );

        assertEquals(3, headers.size());
        assertEquals("api_key test-key, more", headers.get("authorization"));
        assertEquals("value", headers.get("new"));
    }

    @Test
    public void serviceEndpointsDefault() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).mobileKey("test-key").build();
        assertEquals(StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
                config.serviceEndpoints.getStreamingBaseUri());
        assertEquals(StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                config.serviceEndpoints.getPollingBaseUri());
        assertEquals(StandardEndpoints.DEFAULT_EVENTS_BASE_URI,
                config.serviceEndpoints.getEventsBaseUri());
    }

    @Test
    public void serviceEndpointsBuilderNullIsSameAsDefault() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).mobileKey("test-key")
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
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).mobileKey("test-key")
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

    @Test
    public void hooks() {
        Hook mockHook = createMock(Hook.class);
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
            .hooks(
                Components.hooks().setHooks(List.of(mockHook))
            )
            .build();
        assertEquals(1, config.hooks.getHooks().size());
        assertSame(mockHook, config.hooks.getHooks().get(0));
    }
}
