package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DefaultFDv2RequestorTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(10);

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    private static final LDContext CONTEXT = LDContext.create("test-context-key");
    // Bogus paths used by tests — deliberately not the real production paths.
    private static final String GET_REQUEST_PATH = "/fake-poll-get";
    private static final String REPORT_REQUEST_PATH = "/fake-poll-report";
    private static final LDLogger LOGGER = LDLogger.none();

    private static HttpProperties httpProperties() {
        return new HttpProperties(
                10_000,
                new HashMap<>(),
                null, null, null, null,
                10_000,
                null, null);
    }

    private static DefaultFDv2Requestor makeRequestor(HttpServer server, boolean useReport, boolean evaluationReasons, String payloadFilter) {
        return new DefaultFDv2Requestor(
                CONTEXT,
                server.getUri(),
                GET_REQUEST_PATH,
                REPORT_REQUEST_PATH,
                httpProperties(),
                useReport,
                evaluationReasons,
                payloadFilter,
                LOGGER);
    }

    private static DefaultFDv2Requestor makeRequestor(HttpServer server) {
        return makeRequestor(server, false, false, null);
    }

    // Valid FDv2 polling response with multiple events
    private static final String VALID_EVENTS_JSON = "{\n" +
            "  \"events\": [\n" +
            "    {\"event\": \"server-intent\", \"data\": {\"payloads\": [{\"id\": \"p1\", \"target\": 100, \"intentCode\": \"xfer-full\", \"reason\": \"\"}]}},\n" +
            "    {\"event\": \"put-object\", \"data\": {\"version\": 150, \"kind\": \"flag-eval\", \"key\": \"f1\", \"object\": {\"key\": \"f1\", \"version\": 1, \"on\": true}}},\n" +
            "    {\"event\": \"payload-transferred\", \"data\": {\"state\": \"(p:p1:100)\", \"version\": 100}}\n" +
            "  ]\n" +
            "}";

    private static final String EMPTY_EVENTS_JSON = "{\"events\": []}";

    @Test
    public void successfulRequestWithEvents() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(VALID_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                FDv2Requestor.FDv2PayloadResponse response = future.get(5, TimeUnit.SECONDS);

                assertNotNull(response);
                assertTrue(response.isSuccess());
                assertNotNull(response.getEvents());
                assertEquals(3, response.getEvents().size());
                assertEquals("server-intent", response.getEvents().get(0).getEventType());
                assertEquals("put-object", response.getEvents().get(1).getEventType());
                assertEquals("payload-transferred", response.getEvents().get(2).getEventType());

                RequestInfo req = server.getRecorder().requireRequest();
                assertTrue("path should start with " + GET_REQUEST_PATH, req.getPath().startsWith(GET_REQUEST_PATH));
            }
        }
    }

    @Test
    public void emptyEventsArray() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                FDv2Requestor.FDv2PayloadResponse response = future.get(5, TimeUnit.SECONDS);

                assertNotNull(response);
                assertTrue(response.isSuccess());
                assertNotNull(response.getEvents());
                assertTrue(response.getEvents().isEmpty());
            }
        }
    }

    @Test
    public void requestWithBasisQueryParameter() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.make(42, "test-state"));
                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertTrue(req.getPath().startsWith(GET_REQUEST_PATH));
                assertTrue("query should contain basis=test-state", req.getQuery() != null && req.getQuery().contains("basis=test-state"));
            }
        }
    }

    @Test
    public void http304NotModified() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(304))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                FDv2Requestor.FDv2PayloadResponse response = future.get(5, TimeUnit.SECONDS);

                assertNotNull(response);
                assertTrue(response.isSuccess());
                assertEquals(304, response.getStatusCode());
            }
        }
    }

    @Test
    public void etagCachingWith304NotModified() throws Exception {
        Handler cacheableResp = Handlers.all(
                Handlers.header("ETag", "my-etag-value"),
                Handlers.bodyJson(VALID_EVENTS_JSON)
        );
        Handler cachedResp = Handlers.status(304);
        Handler sequence = Handlers.sequential(cacheableResp, cachedResp);

        try (HttpServer server = HttpServer.start(sequence)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future1 = requestor.poll(Selector.EMPTY);
                FDv2Requestor.FDv2PayloadResponse response1 = future1.get(5, TimeUnit.SECONDS);
                assertNotNull(response1);
                assertEquals(3, response1.getEvents().size());

                RequestInfo req1 = server.getRecorder().requireRequest();
                assertNull(req1.getHeader("If-None-Match"));

                Future<FDv2Requestor.FDv2PayloadResponse> future2 = requestor.poll(Selector.EMPTY);
                FDv2Requestor.FDv2PayloadResponse response2 = future2.get(5, TimeUnit.SECONDS);
                assertEquals(304, response2.getStatusCode());

                RequestInfo req2 = server.getRecorder().requireRequest();
                assertEquals("my-etag-value", req2.getHeader("If-None-Match"));
            }
        }
    }

    @Test
    public void httpErrorReturnsFailureResponse() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(401))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                FDv2Requestor.FDv2PayloadResponse response = future.get(5, TimeUnit.SECONDS);

                assertNotNull(response);
                assertFalse(response.isSuccess());
                assertEquals(401, response.getStatusCode());
            }
        }
    }

    @Test
    public void http500ReturnsFailureResponse() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(500))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                FDv2Requestor.FDv2PayloadResponse response = future.get(5, TimeUnit.SECONDS);

                assertNotNull(response);
                assertFalse(response.isSuccess());
                assertEquals(500, response.getStatusCode());
            }
        }
    }

    @Test(expected = ExecutionException.class)
    public void invalidJsonThrows() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson("not json"))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                future.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void missingEventsPropertyThrowsException() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson("{}"))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                try {
                    future.get(5, TimeUnit.SECONDS);
                    fail("Expected ExecutionException");
                } catch (ExecutionException e) {
                    assertNotNull(e.getCause());
                }
            }
        }
    }

    @Test
    public void payloadFilterAddedToRequestWhenSet() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server, false, false, "my-filter")) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertNotNull(req.getQuery());
                assertTrue("query should contain filter=my-filter", req.getQuery().contains("filter=my-filter"));
            }
        }
    }

    @Test
    public void payloadFilterNotAddedWhenNull() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                if (req.getQuery() != null) {
                    assertFalse("query should not contain filter=", req.getQuery().contains("filter="));
                }
            }
        }
    }

    @Test
    public void requestWithBasisContainingState() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.make(0, "(p:payload-1:100)"));
                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertTrue(req.getPath().startsWith(GET_REQUEST_PATH));
                assertTrue("query should contain URL-encoded basis", req.getQuery() != null && req.getQuery().contains("basis=%28p%3Apayload-1%3A100%29"));
            }
        }
    }

    @Test
    public void requestWithComplexBasisState() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.make(100, "(p:my-payload:200)"));
                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertTrue(req.getPath().startsWith(GET_REQUEST_PATH));
                assertTrue("query should contain URL-encoded basis", req.getQuery() != null && req.getQuery().contains("basis=%28p%3Amy-payload%3A200%29"));
            }
        }
    }

    @Test
    public void etagUpdatedOnNewResponse() throws Exception {
        Handler resp1 = Handlers.all(
                Handlers.header("ETag", "etag-1"),
                Handlers.bodyJson(VALID_EVENTS_JSON)
        );
        Handler resp2 = Handlers.all(
                Handlers.header("ETag", "etag-2"),
                Handlers.bodyJson(EMPTY_EVENTS_JSON)
        );
        Handler resp3 = Handlers.status(304);
        Handler sequence = Handlers.sequential(resp1, resp2, resp3);

        try (HttpServer server = HttpServer.start(sequence)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req1 = server.getRecorder().requireRequest();
                assertNull(req1.getHeader("If-None-Match"));

                requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req2 = server.getRecorder().requireRequest();
                assertEquals("etag-1", req2.getHeader("If-None-Match"));

                requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req3 = server.getRecorder().requireRequest();
                assertEquals("etag-2", req3.getHeader("If-None-Match"));
            }
        }
    }

    @Test
    public void etagRemovedWhenNotInResponse() throws Exception {
        Handler resp1 = Handlers.all(
                Handlers.header("ETag", "etag-1"),
                Handlers.bodyJson(VALID_EVENTS_JSON)
        );
        Handler resp2 = Handlers.bodyJson(EMPTY_EVENTS_JSON);
        Handler resp3 = Handlers.bodyJson(EMPTY_EVENTS_JSON);
        Handler sequence = Handlers.sequential(resp1, resp2, resp3);

        try (HttpServer server = HttpServer.start(sequence)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                server.getRecorder().requireRequest();

                requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req2 = server.getRecorder().requireRequest();
                assertEquals("etag-1", req2.getHeader("If-None-Match"));

                requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req3 = server.getRecorder().requireRequest();
                assertNull(req3.getHeader("If-None-Match"));
            }
        }
    }

    @Test
    public void http404ReturnsFailureResponse() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(404))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                FDv2Requestor.FDv2PayloadResponse response = future.get(5, TimeUnit.SECONDS);

                assertNotNull(response);
                assertFalse(response.isSuccess());
                assertEquals(404, response.getStatusCode());
            }
        }
    }

    @Test
    public void baseUriCanHaveContextPath() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            URI uri = server.getUri().resolve("/fake-base");
            try (DefaultFDv2Requestor requestor = new DefaultFDv2Requestor(
                    CONTEXT,
                    uri,
                    GET_REQUEST_PATH,
                    REPORT_REQUEST_PATH,
                    httpProperties(),
                    false,
                    false,
                    null,
                    LOGGER)) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertTrue("path should start with context path + request path", req.getPath().startsWith("/fake-base" + GET_REQUEST_PATH));
            }
        }
    }

    @Test
    public void differentSelectorsUseDifferentEtags() throws Exception {
        Handler resp = Handlers.all(
                Handlers.header("ETag", "etag-for-request"),
                Handlers.bodyJson(EMPTY_EVENTS_JSON)
        );

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Selector selector1 = Selector.make(100, "(p:payload-1:100)");
                Selector selector2 = Selector.make(200, "(p:payload-2:200)");

                requestor.poll(selector1).get(5, TimeUnit.SECONDS);
                RequestInfo req1 = server.getRecorder().requireRequest();
                assertNull(req1.getHeader("If-None-Match"));

                requestor.poll(selector1).get(5, TimeUnit.SECONDS);
                RequestInfo req2 = server.getRecorder().requireRequest();
                assertEquals("etag-for-request", req2.getHeader("If-None-Match"));

                requestor.poll(selector2).get(5, TimeUnit.SECONDS);
                RequestInfo req3 = server.getRecorder().requireRequest();
                assertNull(req3.getHeader("If-None-Match"));
            }
        }
    }

    @Test
    public void payloadFilterWithSelectorBothAddedToRequest() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server, false, false, "myFilter")) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.make(42, "test-state"));
                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertTrue("query should contain basis", req.getQuery() != null && req.getQuery().contains("basis=test-state"));
                assertTrue("query should contain filter", req.getQuery() != null && req.getQuery().contains("filter=myFilter"));
            }
        }
    }

    @Test
    public void payloadFilterNotAddedWhenEmpty() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server, false, false, "")) {
                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                if (req.getQuery() != null) {
                    assertFalse("query should not contain filter=", req.getQuery().contains("filter="));
                }
            }
        }
    }

    // ---- x-ld-fd-fallback header detection ----

    @Test
    public void fdv1FallbackHeaderOnSuccess() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.header("x-ld-fd-fallback", "true"),
                Handlers.bodyJson(VALID_EVENTS_JSON)))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                FDv2Requestor.FDv2PayloadResponse response = requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                assertTrue(response.isSuccess());
                assertTrue(response.isFdv1Fallback());
            }
        }
    }

    @Test
    public void fdv1FallbackHeaderAbsent() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(VALID_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                FDv2Requestor.FDv2PayloadResponse response = requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                assertTrue(response.isSuccess());
                assertFalse(response.isFdv1Fallback());
            }
        }
    }

    @Test
    public void fdv1FallbackHeaderCaseInsensitive() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.header("x-ld-fd-fallback", "True"),
                Handlers.bodyJson(VALID_EVENTS_JSON)))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                FDv2Requestor.FDv2PayloadResponse response = requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                assertTrue(response.isFdv1Fallback());
            }
        }
    }

    @Test
    public void fdv1FallbackHeaderOnFailure() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.header("x-ld-fd-fallback", "true"),
                Handlers.status(500)))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                FDv2Requestor.FDv2PayloadResponse response = requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                assertFalse(response.isSuccess());
                assertTrue(response.isFdv1Fallback());
            }
        }
    }

    @Test
    public void fdv1FallbackHeaderFalseValue() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.header("x-ld-fd-fallback", "false"),
                Handlers.bodyJson(VALID_EVENTS_JSON)))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                FDv2Requestor.FDv2PayloadResponse response = requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                assertFalse(response.isFdv1Fallback());
            }
        }
    }

    @Test
    public void evaluationReasonsAddedToRequest() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server, false, true, null)) {
                requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertTrue("query should contain withReasons=true",
                        req.getQuery() != null && req.getQuery().contains("withReasons=true"));
            }
        }
    }

    @Test
    public void useReportMethod() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(EMPTY_EVENTS_JSON))) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server, true, false, null)) {
                requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals("REPORT", req.getMethod());
                // Context should be in the request body, not the URL path
                assertTrue("path should use REPORT path without context segment",
                        req.getPath().startsWith(REPORT_REQUEST_PATH));
                assertFalse("path should not contain GET context segment",
                        req.getPath().startsWith(GET_REQUEST_PATH));
                assertNotNull("body should contain serialized context", req.getBody());
                assertTrue("body should contain context key",
                        req.getBody().contains("test-context-key"));
            }
        }
    }

    @Test
    public void useReportMethodDoesNotUseEtag() throws Exception {
        // ETag caching is disabled for REPORT requests since the context is in the body
        Handler etagResp = Handlers.all(
                Handlers.header("ETag", "some-etag"),
                Handlers.bodyJson(EMPTY_EVENTS_JSON));

        try (HttpServer server = HttpServer.start(etagResp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server, true, false, null)) {
                // First REPORT request — server returns an ETag, but it should not be cached
                requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req1 = server.getRecorder().requireRequest();
                assertNull("REPORT request should not send If-None-Match", req1.getHeader("If-None-Match"));

                // Second REPORT request — still no If-None-Match because ETag was never stored
                requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req2 = server.getRecorder().requireRequest();
                assertNull("REPORT request should never send If-None-Match", req2.getHeader("If-None-Match"));
            }
        }
    }

    @Test
    public void http400LogsProguardWarning() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(400))) {
            try (DefaultFDv2Requestor requestor = new DefaultFDv2Requestor(
                    CONTEXT, server.getUri(), GET_REQUEST_PATH, REPORT_REQUEST_PATH,
                    httpProperties(), false, false, null, logging.logger)) {

                Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(Selector.EMPTY);
                FDv2Requestor.FDv2PayloadResponse response = future.get(5, TimeUnit.SECONDS);

                assertNotNull(response);
                assertFalse(response.isSuccess());
                assertEquals(400, response.getStatusCode());
                logging.assertErrorLogged("ProGuard");
            }
        }
    }

    @Test(expected = ExecutionException.class)
    public void networkFailureThrowsException() throws Exception {
        try (DefaultFDv2Requestor requestor = new DefaultFDv2Requestor(
                CONTEXT, URI.create("http://localhost:1"),
                GET_REQUEST_PATH, REPORT_REQUEST_PATH,
                httpProperties(), false, false, null, LOGGER)) {
            requestor.poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
        }
    }
}
