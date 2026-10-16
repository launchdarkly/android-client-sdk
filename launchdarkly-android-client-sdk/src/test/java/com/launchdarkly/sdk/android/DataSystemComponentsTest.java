package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the request wiring of the data source builder implementations in
 * {@link DataSystemComponents}: which HTTP method, path, and body each FDv2
 * component and the FDv1 fallback synchronizer use for a given HTTP configuration.
 */
public class DataSystemComponentsTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(10);

    @Rule
    public TemporaryFolder cacheDir = new TemporaryFolder();

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

    private static final LDContext CONTEXT = LDContext.create("test-context-key");
    private static final LDLogger LOGGER = LDLogger.none();

    // A valid FDv2 polling response that produces a changeset when processed
    private static final String VALID_POLL_RESPONSE_JSON =
            "{\"events\":[" +
            "{\"event\":\"server-intent\",\"data\":{\"payloads\":[{\"id\":\"p1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"\"}]}}," +
            "{\"event\":\"payload-transferred\",\"data\":{\"state\":\"(p:p1:100)\",\"version\":100}}" +
            "]}";

    // A valid FDv1 polling response (flag key to flag object map)
    private static final String VALID_FDV1_JSON =
            "{\"flag1\":{\"key\":\"flag1\",\"version\":1,\"value\":true}}";

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    private static HttpConfiguration httpConfig(boolean useReport, boolean usePost) {
        return new HttpConfiguration(10_000, Collections.emptyMap(), null, useReport, usePost);
    }

    private DataSourceBuildInputsInternal makeInputs(HttpServer server, HttpConfiguration http) {
        ServiceEndpoints endpoints = new ServiceEndpoints(
                server.getUri(), server.getUri(), server.getUri());
        return new DataSourceBuildInputsInternal(
                CONTEXT, endpoints, http, false,
                () -> Selector.EMPTY, executor, cacheDir.getRoot(), LOGGER, null);
    }

    @Test
    public void pollingSynchronizerSendsPostWhenUsePostEnabled() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(VALID_POLL_RESPONSE_JSON))) {
            Synchronizer sync = new DataSystemComponents.PollingSynchronizerBuilderImpl()
                    .build(makeInputs(server, httpConfig(false, true)));
            try {
                sync.next().get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals("POST", req.getMethod());
                assertEquals("/sdk/poll/eval", req.getPath());
                assertNotNull("body should contain the serialized context", req.getBody());
                assertTrue("body should contain the context key",
                        req.getBody().contains("test-context-key"));
            } finally {
                sync.close();
            }
        }
    }

    @Test
    public void pollingSynchronizerSendsGetWhenOnlyUseReportEnabled() throws Exception {
        // useReport has no effect on FDv2 components; the request stays GET
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(VALID_POLL_RESPONSE_JSON))) {
            Synchronizer sync = new DataSystemComponents.PollingSynchronizerBuilderImpl()
                    .build(makeInputs(server, httpConfig(true, false)));
            try {
                sync.next().get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals("GET", req.getMethod());
                assertTrue("path should use the GET endpoint with a context segment",
                        req.getPath().startsWith("/sdk/poll/eval/"));
                assertTrue("path should contain the base64 context",
                        req.getPath().contains(LDUtil.urlSafeBase64(CONTEXT)));
            } finally {
                sync.close();
            }
        }
    }

    @Test
    public void streamingSynchronizerSendsPostWhenUsePostEnabled() throws Exception {
        String serverIntent = "event: server-intent\ndata: {\"payloads\":[{\"id\":\"p1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"\"}]}";
        String payloadTransferred = "event: payload-transferred\ndata: {\"state\":\"(p:p1:100)\",\"version\":100}";

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {
            Synchronizer sync = new DataSystemComponents.StreamingSynchronizerBuilderImpl()
                    .build(makeInputs(server, httpConfig(false, true)));
            try {
                FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);
                assertNotNull(result);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals("POST", req.getMethod());
                assertEquals("/sdk/stream/eval", req.getPath());
                assertNotNull("body should contain the serialized context", req.getBody());
                assertTrue("body should contain the context key",
                        req.getBody().contains("test-context-key"));
            } finally {
                sync.close();
            }
        }
    }

    @Test
    public void fdv1FallbackSynchronizerSendsReportWhenUsePostEnabled() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(VALID_FDV1_JSON))) {
            Synchronizer sync = new DataSystemComponents.FDv1PollingSynchronizerBuilderImpl()
                    .build(makeInputs(server, httpConfig(false, true)));
            try {
                sync.next().get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals("REPORT", req.getMethod());
                assertEquals("/msdk/evalx/context", req.getPath());
                assertNotNull("body should contain the serialized context", req.getBody());
                assertTrue("body should contain the context key",
                        req.getBody().contains("test-context-key"));
            } finally {
                sync.close();
            }
        }
    }

    @Test
    public void fdv1FallbackSynchronizerSendsGetByDefault() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(VALID_FDV1_JSON))) {
            Synchronizer sync = new DataSystemComponents.FDv1PollingSynchronizerBuilderImpl()
                    .build(makeInputs(server, httpConfig(false, false)));
            try {
                sync.next().get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals("GET", req.getMethod());
                assertTrue("path should use the FDv1 GET endpoint with a context segment",
                        req.getPath().startsWith("/msdk/evalx/contexts/"));
            } finally {
                sync.close();
            }
        }
    }
}
