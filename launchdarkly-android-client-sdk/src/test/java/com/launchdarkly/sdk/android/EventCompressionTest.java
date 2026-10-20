package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.Rule;
import org.junit.Test;

public class EventCompressionTest {
    private static final LDContext CONTEXT = LDContext.create("context-key");
    private static final String MOBILE_KEY = "test-mobile-key";
    private static final String EVENT_KEY = "compression-test-event";

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    @Test
    public void eventPayloadsAreGzipEncodedByDefault() throws Exception {
        RequestInfo request = sendOneEvent(Components.sendEvents());

        assertEquals("gzip", request.getHeader("Content-Encoding"));
        assertFalse("the payload should not be sent as plain JSON", request.getBody().contains(EVENT_KEY));
    }

    @Test
    public void eventPayloadsAreSentUncompressedWhenCompressionIsDisabled() throws Exception {
        RequestInfo request = sendOneEvent(Components.sendEvents().enableCompression(false));

        assertNull(request.getHeader("Content-Encoding"));
        assertTrue("the payload should be sent as plain JSON", request.getBody().contains(EVENT_KEY));
    }

    private RequestInfo sendOneEvent(EventProcessorBuilder eventsBuilder) throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(202))) {
            LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                    .mobileKey(MOBILE_KEY)
                    .diagnosticOptOut(true)
                    .serviceEndpoints(Components.serviceEndpoints().events(server.getUri()))
                    .build();
            ClientContext clientContext = ClientContextImpl.fromConfig(config, MOBILE_KEY, "",
                    null, null, CONTEXT, logging.logger, new MockPlatformState(),
                    new EnvironmentReporterBuilder().build(), new SimpleTestTaskExecutor());

            try (EventProcessor eventProcessor = eventsBuilder.build(clientContext)) {
                eventProcessor.setOffline(false);
                eventProcessor.recordCustomEvent(CONTEXT, EVENT_KEY, LDValue.ofNull(), null);
                eventProcessor.flush();
                return server.getRecorder().requireRequest();
            }
        }
    }
}
