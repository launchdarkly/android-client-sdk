package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuildInputs;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that the built-in FDv2 data source builders wire the build inputs into the
 * requests they send: the request method, the request path, and where the context travels.
 */
public class DataSystemComponentsTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(15);

    private static final LDContext CONTEXT = LDContext.create("wiring-test-context-key");
    private static final File CACHE_DIR = new File(System.getProperty("java.io.tmpdir"));

    private static final String FDV2_POLL_RESPONSE_JSON =
            "{\"events\":[" +
            "{\"event\":\"server-intent\",\"data\":{\"payloads\":[{\"id\":\"p1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"\"}]}}," +
            "{\"event\":\"payload-transferred\",\"data\":{\"state\":\"(p:p1:100)\",\"version\":100}}" +
            "]}";

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    private DataSourceBuildInputs makeInputs(HttpServer server, boolean usePost) {
        ServiceEndpoints endpoints = Components.serviceEndpoints()
                .polling(server.getUri())
                .streaming(server.getUri())
                .events(server.getUri())
                .createServiceEndpoints();
        HttpConfiguration http = new HttpConfiguration(10_000, Collections.emptyMap(), null, false);
        return new DataSourceBuildInputs(
                CONTEXT, endpoints, http, false, usePost,
                () -> Selector.EMPTY, executor, CACHE_DIR, LDLogger.none());
    }

    private static String contextPathSegment() {
        return "/" + LDUtil.urlSafeBase64(CONTEXT);
    }

    private static void assertContextInBody(RequestInfo request) {
        assertNotNull("body should contain the serialized context", request.getBody());
        assertTrue("body should contain the context key",
                request.getBody().contains("wiring-test-context-key"));
    }

    private static void assertNoBody(RequestInfo request) {
        assertTrue("request should have no body",
                request.getBody() == null || request.getBody().isEmpty());
    }

    // ---- polling initializer ----

    @Test
    public void pollingInitializer_defaultUsesGetWithContextInPath() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(FDV2_POLL_RESPONSE_JSON))) {
            Initializer initializer = new DataSystemComponents.PollingInitializerBuilderImpl()
                    .build(makeInputs(server, false));
            initializer.run().get(5, TimeUnit.SECONDS);
            initializer.close();

            RequestInfo request = server.getRecorder().requireRequest();
            assertEquals("GET", request.getMethod());
            assertEquals(StandardEndpoints.FDV2_POLLING_REQUEST_BASE_PATH + contextPathSegment(),
                    request.getPath());
            assertNoBody(request);
        }
    }

    @Test
    public void pollingInitializer_usePostSendsContextInBody() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(FDV2_POLL_RESPONSE_JSON))) {
            Initializer initializer = new DataSystemComponents.PollingInitializerBuilderImpl()
                    .build(makeInputs(server, true));
            initializer.run().get(5, TimeUnit.SECONDS);
            initializer.close();

            RequestInfo request = server.getRecorder().requireRequest();
            assertEquals("POST", request.getMethod());
            assertEquals(StandardEndpoints.FDV2_POLLING_REQUEST_BASE_PATH, request.getPath());
            assertContextInBody(request);
        }
    }

    // ---- polling synchronizer ----

    @Test
    public void pollingSynchronizer_usePostSendsContextInBody() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(FDV2_POLL_RESPONSE_JSON))) {
            Synchronizer synchronizer = new DataSystemComponents.PollingSynchronizerBuilderImpl()
                    .build(makeInputs(server, true));
            synchronizer.next().get(5, TimeUnit.SECONDS);
            synchronizer.close();

            RequestInfo request = server.getRecorder().requireRequest();
            assertEquals("POST", request.getMethod());
            assertEquals(StandardEndpoints.FDV2_POLLING_REQUEST_BASE_PATH, request.getPath());
            assertContextInBody(request);
        }
    }

    // ---- streaming synchronizer ----

    @Test
    public void streamingSynchronizer_defaultUsesGetWithContextInPath() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.leaveOpen()))) {
            Synchronizer synchronizer = new DataSystemComponents.StreamingSynchronizerBuilderImpl()
                    .build(makeInputs(server, false));
            synchronizer.next();

            RequestInfo request = server.getRecorder().requireRequest();
            synchronizer.close();

            assertEquals("GET", request.getMethod());
            assertEquals(StandardEndpoints.FDV2_STREAMING_REQUEST_BASE_PATH + contextPathSegment(),
                    request.getPath());
            assertNoBody(request);
        }
    }

    @Test
    public void streamingSynchronizer_usePostSendsContextInBody() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.leaveOpen()))) {
            Synchronizer synchronizer = new DataSystemComponents.StreamingSynchronizerBuilderImpl()
                    .build(makeInputs(server, true));
            synchronizer.next();

            RequestInfo request = server.getRecorder().requireRequest();
            synchronizer.close();

            assertEquals("POST", request.getMethod());
            assertEquals(StandardEndpoints.FDV2_STREAMING_REQUEST_BASE_PATH, request.getPath());
            assertContextInBody(request);
        }
    }

    // ---- FDv1 fallback synchronizer ----

    @Test
    public void fdv1FallbackSynchronizer_defaultUsesGetWithContextInPath() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson("{}"))) {
            Synchronizer synchronizer = new DataSystemComponents.FDv1PollingSynchronizerBuilderImpl()
                    .build(makeInputs(server, false));
            synchronizer.next().get(5, TimeUnit.SECONDS);
            synchronizer.close();

            RequestInfo request = server.getRecorder().requireRequest();
            assertEquals("GET", request.getMethod());
            assertEquals(StandardEndpoints.POLLING_REQUEST_GET_BASE_PATH + contextPathSegment(),
                    request.getPath());
            assertNoBody(request);
        }
    }

    @Test
    public void fdv1FallbackSynchronizer_usePostSendsContextInBodyWithReport() throws Exception {
        // The FDv1 endpoints do not accept POST; REPORT is their body-carrying method.
        try (HttpServer server = HttpServer.start(Handlers.bodyJson("{}"))) {
            Synchronizer synchronizer = new DataSystemComponents.FDv1PollingSynchronizerBuilderImpl()
                    .build(makeInputs(server, true));
            synchronizer.next().get(5, TimeUnit.SECONDS);
            synchronizer.close();

            RequestInfo request = server.getRecorder().requireRequest();
            assertEquals("REPORT", request.getMethod());
            assertEquals(StandardEndpoints.POLLING_REQUEST_REPORT_BASE_PATH, request.getPath());
            assertContextInBody(request);
        }
    }
}
