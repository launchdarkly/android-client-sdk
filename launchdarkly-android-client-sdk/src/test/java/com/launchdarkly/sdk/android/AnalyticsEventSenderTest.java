package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.ServerTLSConfiguration;

import org.junit.Rule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class AnalyticsEventSenderTest {
    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    @Test
    public void certificateFailureTakesTheNormalBackoff() throws Exception {
        // The client does not trust a self-signed certificate, so the post fails in the TLS handshake.
        try (HttpServer server = HttpServer.startSecure(ServerTLSConfiguration.makeSelfSignedCertificate(),
                Handlers.status(202));
             AnalyticsEventSender sender = new AnalyticsEventSender(LDUtil.makeHttpProperties(
                     new HttpConfiguration(2000, Collections.emptyMap(), null, false)),
                     false, logging.logger)) {
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);

            AnalyticsEventSender.Result result = sender.sendBatch(
                    new EventLogFormat.Payload(body, body.length, 0), 0, "payload-id", server.getUri());

            assertFalse(result.success);
            assertEquals(0, result.status);
            assertFalse(result.unexpected);
        }
    }
}
