package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FDv2StreamingSynchronizerTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(10);

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    private static final LDContext CONTEXT = LDContext.create("test-context");
    private static final LDLogger LOGGER = LDLogger.none();
    private static final SelectorSource EMPTY_SELECTOR_SOURCE = () -> Selector.EMPTY;

    // Bogus paths used by tests — deliberately not the real production paths.
    private static final String STREAM_PATH = "/fake-stream";
    private static final String POLL_GET_PATH = "/fake-poll-get";
    private static final String POLL_REPORT_PATH = "/fake-poll-report";

    private static HttpProperties httpProperties() {
        return new HttpProperties(
                10_000,
                new HashMap<>(),
                null, null, null, null,
                10_000,
                null, null);
    }

    private FDv2StreamingSynchronizer makeSynchronizer(URI streamBaseUri) {
        return makeSynchronizer(streamBaseUri, EMPTY_SELECTOR_SOURCE, null);
    }

    private FDv2StreamingSynchronizer makeSynchronizer(
            URI streamBaseUri, SelectorSource selectorSource) {
        return makeSynchronizer(streamBaseUri, selectorSource, null);
    }

    private FDv2StreamingSynchronizer makeSynchronizer(
            URI streamBaseUri, SelectorSource selectorSource,
            DiagnosticStore diagnosticStore) {
        return new FDv2StreamingSynchronizer(
                CONTEXT,
                selectorSource,
                streamBaseUri,
                STREAM_PATH,
                null,
                100,
                false,
                false,
                httpProperties(),
                executor,
                LOGGER,
                diagnosticStore);
    }

    private FDv2StreamingSynchronizer makeSynchronizer(
            URI streamBaseUri,
            boolean evaluationReasons, boolean useReport) {
        return new FDv2StreamingSynchronizer(
                CONTEXT, EMPTY_SELECTOR_SOURCE, streamBaseUri, STREAM_PATH,
                null, 100, evaluationReasons, useReport,
                httpProperties(), executor, LOGGER, null);
    }

    private static DiagnosticStore basicDiagnosticStore() {
        return new DiagnosticStore(new DiagnosticStore.SdkDiagnosticParams(
                "mobile-key", "android-client-sdk", "1.0.0", "Android", null, null, null));
    }

    // A valid FDv2 polling response that produces a CHANGE_SET when processed.
    private static final String VALID_POLL_RESPONSE_JSON =
            "{\"events\":[" +
            "{\"event\":\"server-intent\",\"data\":{\"payloads\":[{\"id\":\"p1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"\"}]}}," +
            "{\"event\":\"payload-transferred\",\"data\":{\"state\":\"(p:p1:100)\",\"version\":100}}" +
            "]}";

    // ---- close lifecycle ----

    @Test
    public void closeBeforeAnyNext_succeeds() {
        FDv2StreamingSynchronizer sync = makeSynchronizer(URI.create("http://localhost:9999/"));
        sync.close();
    }

    @Test
    public void closeIdempotent() {
        FDv2StreamingSynchronizer sync = makeSynchronizer(URI.create("http://localhost:9999/"));
        sync.close();
        sync.close();
    }

    @Test
    public void closeCalledMultipleTimes() throws Exception {
        // Close multiple times before calling next(); next() should still return SHUTDOWN
        FDv2StreamingSynchronizer sync = makeSynchronizer(URI.create("http://localhost:9999/"));
        sync.close();
        sync.close();
        sync.close();

        FDv2SourceResult result = sync.next().get(1, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());
    }

    @Test
    public void nextThenClose_futureCompletesWithShutdown() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> future = sync.next();
            sync.close();

            FDv2SourceResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertNotNull(result.getStatus());
            assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());
        }
    }

    @Test
    public void shutdownAfterEventReceived() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());

            // Shutdown after receiving a changeset should still work cleanly
            sync.close();
        }
    }

    // ---- SSE events ----

    @Test
    public void receivesMultipleChangesets() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred1 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");
        String putObject1 = makeEvent("put-object", "{\"kind\":\"flag\",\"key\":\"flag1\",\"version\":1,\"object\":{}}");
        String payloadTransferred2 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:101)\",\"version\":101}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred1),
                Handlers.SSE.event(putObject1),
                Handlers.SSE.event(payloadTransferred2),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> result1Future = sync.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);

            assertNotNull(result1);
            assertEquals(SourceResultType.CHANGE_SET, result1.getResultType());
            assertNotNull(result1.getChangeSet());

            Future<FDv2SourceResult> result2Future = sync.next();
            FDv2SourceResult result2 = result2Future.get(5, TimeUnit.SECONDS);

            assertNotNull(result2);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());
            assertNotNull(result2.getChangeSet());

            sync.close();
        }
    }

    @Test
    public void goodbyeEventInResponse() throws Exception {
        String goodbyeEvent = makeEvent("goodbye", "{\"reason\":\"service-unavailable\"}");
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(goodbyeEvent),
                        Handlers.SSE.leaveOpen()),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.leaveOpen())))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> result1Future = sync.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);

            assertNotNull(result1);
            assertEquals(SourceResultType.STATUS, result1.getResultType());
            assertEquals(SourceSignal.GOODBYE, result1.getStatus().getState());
            assertEquals("service-unavailable", result1.getStatus().getReason());

            Future<FDv2SourceResult> result2Future = sync.next();
            FDv2SourceResult result2 = result2Future.get(5, TimeUnit.SECONDS);

            assertNotNull(result2);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());
            assertNotNull(result2.getChangeSet());

            assertTrue("Should have made at least 2 requests", server.getRecorder().count() >= 2);

            sync.close();
        }
    }

    @Test
    public void heartbeatEvent() throws Exception {
        String heartbeatEvent = makeEvent("heartbeat", "{}");
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(heartbeatEvent),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
            assertNotNull(result.getChangeSet());

            sync.close();
        }
    }

    @Test
    public void errorEventFromServer() throws Exception {
        String errorEvent = makeEvent("error", "{\"id\":\"error-123\",\"reason\":\"some server error\"}");
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(errorEvent),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
            assertNotNull(result.getChangeSet());

            sync.close();
        }
    }

    // ---- error handling ----

    @Test
    public void httpNonRecoverableError() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(401))) {
            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());

            sync.close();
        }
    }

    @Test
    public void httpRecoverableError() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(503))) {
            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());

            sync.close();
        }
    }

    @Test
    public void networkError() throws Exception {
        FDv2StreamingSynchronizer sync = makeSynchronizer(URI.create("http://localhost:1"));

        Future<FDv2SourceResult> resultFuture = sync.next();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());

        sync.close();
    }

    @Test
    public void invalidEventData() throws Exception {
        String badEvent = makeEvent("server-intent", "invalid json");
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(badEvent),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());

            sync.close();
        }
    }

    @Test
    public void invalidEventStructureCausesInterrupt() throws Exception {
        String badEventStructure = makeEvent("put-object", "{}");
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(badEventStructure),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());

            sync.close();
        }
    }

    @Test
    public void protocolInternalErrorCausesInterrupt() throws Exception {
        String payloadTransferred = makeEvent("payload-transferred",
                "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());

            sync.close();
        }
    }

    @Test
    public void changesetTranslationFailureCausesInterrupt() throws Exception {
        String serverIntent = makeEvent("server-intent",
                "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"\"}]}");
        String badPutObject = makeEvent("put-object",
                "{\"version\":1,\"kind\":\"flag_eval\",\"key\":\"f1\",\"object\":123}");
        String payloadTransferred = makeEvent("payload-transferred",
                "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(badPutObject),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());

            sync.close();
        }
    }

    // ---- selector (basis) in stream request ----

    @Test
    public void selectorAddedToStreamRequest() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            SelectorSource selectorSource = () -> Selector.make(50, "(p:old:50)");
            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri(), selectorSource);

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());

            assertEquals(1, server.getRecorder().count());
            RequestInfo request = server.getRecorder().requireRequest();
            assertTrue("query should contain URL-encoded basis",
                    request.getQuery() != null && request.getQuery().contains("basis=%28p%3Aold%3A50%29"));

            sync.close();
        }
    }

    @Test
    public void emptySelector_noBasisInRequest() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            // EMPTY_SELECTOR_SOURCE returns Selector.EMPTY which isEmpty() == true
            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            resultFuture.get(5, TimeUnit.SECONDS);

            RequestInfo request = server.getRecorder().requireRequest();
            assertTrue("query should not contain basis=",
                    request.getQuery() == null || !request.getQuery().contains("basis="));

            sync.close();
        }
    }

    @Test
    public void selectorRefetchedOnReconnection() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        // First connection: 503 error; second connection: successful changeset
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.status(503),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.leaveOpen()),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.leaveOpen())))) {

            // Return different selectors on each call so we can verify each connection reads the selector
            AtomicInteger callCount = new AtomicInteger(0);
            SelectorSource selectorSource = () -> {
                int call = callCount.getAndIncrement();
                return call == 0
                        ? Selector.make(50, "(p:old:50)")
                        : Selector.make(100, "(p:new:100)");
            };

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri(), selectorSource);

            // First result: INTERRUPTED from the 503
            Future<FDv2SourceResult> result1Future = sync.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);
            assertNotNull(result1);
            assertEquals(SourceResultType.STATUS, result1.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result1.getStatus().getState());

            // Drain until we get a CHANGE_SET — the EventSource reconnects automatically
            FDv2SourceResult changesetResult = null;
            for (int i = 0; i < 5; i++) {
                Future<FDv2SourceResult> resultFuture = sync.next();
                FDv2SourceResult r = resultFuture.get(5, TimeUnit.SECONDS);
                assertNotNull(r);
                if (r.getResultType() == SourceResultType.CHANGE_SET) {
                    changesetResult = r;
                    break;
                }
                assertEquals(SourceResultType.STATUS, r.getResultType());
            }
            assertNotNull("Should eventually get a CHANGE_SET after reconnection", changesetResult);

            // Selector was fetched at least twice (once per connection attempt)
            assertTrue("Selector should have been fetched at least twice", callCount.get() >= 2);
            assertTrue("Should have made at least 2 requests", server.getRecorder().count() >= 2);

            sync.close();
        }
    }

    // ---- diagnostic store ----

    @Test
    public void nullDiagnosticStoreDoesNotCauseError() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            // null diagnosticStore is the default in makeSynchronizer; verify it doesn't throw
            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());

            sync.close();
        }
    }

    @Test
    public void streamInitDiagnosticRecordedOnSuccessfulChangeset() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();
        long startTime = System.currentTimeMillis();

        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(
                    server.getUri(), EMPTY_SELECTOR_SOURCE, diagnosticStore);

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());

            long timeAfterOpen = System.currentTimeMillis();
            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();
            LDValue streamInits = event.get("streamInits");
            assertEquals(1, streamInits.size());

            LDValue init = streamInits.get(0);
            assertFalse(init.get("failed").booleanValue());
            assertTrue(init.get("timestamp").longValue() >= startTime);
            assertTrue(init.get("timestamp").longValue() <= timeAfterOpen);
            assertTrue(init.get("durationMillis").longValue() <= timeAfterOpen - startTime);

            sync.close();
        }
    }

    @Test
    public void streamInitDiagnosticRecordedOnErrorDuringInit() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();
        long startTime = System.currentTimeMillis();

        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        // First connection: 503 error; second connection: successful changeset
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.status(503),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.leaveOpen())))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(
                    server.getUri(), EMPTY_SELECTOR_SOURCE, diagnosticStore);

            // First result: error
            Future<FDv2SourceResult> result1Future = sync.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result1.getResultType());

            // Second result: successful changeset
            Future<FDv2SourceResult> result2Future = sync.next();
            FDv2SourceResult result2 = result2Future.get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());

            long timeAfterOpen = System.currentTimeMillis();
            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();
            LDValue streamInits = event.get("streamInits");
            assertEquals(2, streamInits.size());

            LDValue init0 = streamInits.get(0);
            assertTrue(init0.get("failed").booleanValue());
            assertTrue(init0.get("timestamp").longValue() >= startTime);
            assertTrue(init0.get("timestamp").longValue() <= timeAfterOpen);

            LDValue init1 = streamInits.get(1);
            assertFalse(init1.get("failed").booleanValue());
            assertTrue(init1.get("timestamp").longValue() >= init0.get("timestamp").longValue());
            assertTrue(init1.get("timestamp").longValue() <= timeAfterOpen);

            sync.close();
        }
    }

    @Test
    public void streamRestartNotRecordedAsFailed() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();

        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred1 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");
        String goodbyeEvent = makeEvent("goodbye", "{\"reason\":\"service-unavailable\"}");
        String payloadTransferred2 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:101)\",\"version\":101}");

        // First connection: changeset + goodbye; second connection: changeset
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred1),
                        Handlers.SSE.event(goodbyeEvent),
                        Handlers.SSE.leaveOpen()),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred2),
                        Handlers.SSE.leaveOpen())))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(
                    server.getUri(), EMPTY_SELECTOR_SOURCE, diagnosticStore);

            // First changeset
            Future<FDv2SourceResult> result1Future = sync.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result1.getResultType());

            // Goodbye
            Future<FDv2SourceResult> result2Future = sync.next();
            FDv2SourceResult result2 = result2Future.get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result2.getResultType());
            assertEquals(SourceSignal.GOODBYE, result2.getStatus().getState());

            // Second changeset after reconnect
            Future<FDv2SourceResult> result3Future = sync.next();
            FDv2SourceResult result3 = result3Future.get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result3.getResultType());

            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();
            LDValue streamInits = event.get("streamInits");
            assertEquals(2, streamInits.size());
            // Goodbye is a deliberate restart — neither init should be marked failed
            assertFalse(streamInits.get(0).get("failed").booleanValue());
            assertFalse(streamInits.get(1).get("failed").booleanValue());

            sync.close();
        }
    }

    @Test
    public void multipleRestartsRecordMultipleDiagnostics() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();

        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred1 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");
        String goodbyeEvent1 = makeEvent("goodbye", "{\"reason\":\"service-unavailable\"}");
        String payloadTransferred2 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:101)\",\"version\":101}");
        String goodbyeEvent2 = makeEvent("goodbye", "{\"reason\":\"service-unavailable\"}");
        String payloadTransferred3 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:102)\",\"version\":102}");

        // Three connections: changeset+goodbye, changeset+goodbye, changeset
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred1),
                        Handlers.SSE.event(goodbyeEvent1),
                        Handlers.SSE.leaveOpen()),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred2),
                        Handlers.SSE.event(goodbyeEvent2),
                        Handlers.SSE.leaveOpen()),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred3),
                        Handlers.SSE.leaveOpen())))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(
                    server.getUri(), EMPTY_SELECTOR_SOURCE, diagnosticStore);

            FDv2SourceResult r1 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, r1.getResultType());

            FDv2SourceResult r2 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, r2.getResultType());

            FDv2SourceResult r3 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, r3.getResultType());

            FDv2SourceResult r4 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, r4.getResultType());

            FDv2SourceResult r5 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, r5.getResultType());

            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();
            LDValue streamInits = event.get("streamInits");
            assertEquals(3, streamInits.size());
            assertFalse(streamInits.get(0).get("failed").booleanValue());
            assertFalse(streamInits.get(1).get("failed").booleanValue());
            assertFalse(streamInits.get(2).get("failed").booleanValue());

            sync.close();
        }
    }

    @Test
    public void streamRestartAfterInvalidDataRecordsMultipleDiagnostics() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();

        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String invalidPayload = makeEvent("payload-transferred", "{malformed json}");
        String validPayload = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        // First connection: invalid data causes restart; second connection: valid changeset
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(invalidPayload),
                        Handlers.SSE.leaveOpen()),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(validPayload),
                        Handlers.SSE.leaveOpen())))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(
                    server.getUri(), EMPTY_SELECTOR_SOURCE, diagnosticStore);

            // First result: interrupted due to invalid data
            Future<FDv2SourceResult> result1Future = sync.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result1.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result1.getStatus().getState());

            // Second result: valid changeset
            Future<FDv2SourceResult> result2Future = sync.next();
            FDv2SourceResult result2 = result2Future.get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());

            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();
            LDValue streamInits = event.get("streamInits");
            assertEquals(2, streamInits.size());
            assertTrue("Invalid data restart should record a failed stream init",
                    streamInits.get(0).get("failed").booleanValue());
            assertFalse(streamInits.get(1).get("failed").booleanValue());

            sync.close();
        }
    }

    @Test
    public void multipleErrorsRecordMultipleDiagnostics() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();
        long startTime = System.currentTimeMillis();

        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        // Three connections: 503, 503, successful changeset
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.status(503),
                Handlers.status(503),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.leaveOpen())))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(
                    server.getUri(), EMPTY_SELECTOR_SOURCE, diagnosticStore);

            // First error
            FDv2SourceResult result1 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result1.getResultType());

            // Second error
            FDv2SourceResult result2 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result2.getResultType());

            // Successful changeset
            FDv2SourceResult result3 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result3.getResultType());

            long timeAfterOpen = System.currentTimeMillis();
            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();
            LDValue streamInits = event.get("streamInits");
            assertEquals(3, streamInits.size());

            LDValue init0 = streamInits.get(0);
            assertTrue(init0.get("failed").booleanValue());
            assertTrue(init0.get("timestamp").longValue() >= startTime);
            assertTrue(init0.get("timestamp").longValue() <= timeAfterOpen);

            LDValue init1 = streamInits.get(1);
            assertTrue(init1.get("failed").booleanValue());
            assertTrue(init1.get("timestamp").longValue() >= init0.get("timestamp").longValue());
            assertTrue(init1.get("timestamp").longValue() <= timeAfterOpen);

            LDValue init2 = streamInits.get(2);
            assertFalse(init2.get("failed").booleanValue());
            assertTrue(init2.get("timestamp").longValue() >= init1.get("timestamp").longValue());
            assertTrue(init2.get("timestamp").longValue() <= timeAfterOpen);

            sync.close();
        }
    }

    @Test
    public void errorAfterSuccessfulChangesetRecordsNewDiagnostic() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();

        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");
        String goodbyeEvent = makeEvent("goodbye", "{\"reason\":\"service-unavailable\"}");

        // First connection: changeset + goodbye; second connection: 503; third connection: changeset
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.event(goodbyeEvent),
                        Handlers.SSE.leaveOpen()),
                Handlers.status(503),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.leaveOpen())))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(
                    server.getUri(), EMPTY_SELECTOR_SOURCE, diagnosticStore);

            // First successful changeset
            FDv2SourceResult result1 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result1.getResultType());

            // Goodbye
            FDv2SourceResult result2 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result2.getResultType());
            assertEquals(SourceSignal.GOODBYE, result2.getStatus().getState());

            // Error (503)
            FDv2SourceResult result3 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result3.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result3.getStatus().getState());

            // Second successful changeset
            FDv2SourceResult result4 = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result4.getResultType());

            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();
            LDValue streamInits = event.get("streamInits");
            assertEquals(3, streamInits.size());
            // First init: successful, second init: failed (503), third init: successful
            assertFalse(streamInits.get(0).get("failed").booleanValue());
            assertTrue(streamInits.get(1).get("failed").booleanValue());
            assertFalse(streamInits.get(2).get("failed").booleanValue());

            sync.close();
        }
    }

    // ---- stream URL construction ----

    @Test
    public void contextBase64AppendedToStreamUrl() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            sync.next().get(5, TimeUnit.SECONDS);

            RequestInfo request = server.getRecorder().requireRequest();
            String expectedContextSegment = LDUtil.urlSafeBase64(CONTEXT);
            assertTrue("path should contain base64-encoded context segment",
                    request.getPath().contains(expectedContextSegment));

            sync.close();
        }
    }

    @Test
    public void evaluationReasonsAddedToStreamUrl() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri(),
                    true /* evaluationReasons */, false);

            sync.next().get(5, TimeUnit.SECONDS);

            RequestInfo request = server.getRecorder().requireRequest();
            assertTrue("query should contain withReasons=true",
                    request.getQuery() != null && request.getQuery().contains("withReasons=true"));

            sync.close();
        }
    }

    @Test
    public void useReportMethodForStreamRequest() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri(),
                    false, true /* useReport */);

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());

            RequestInfo request = server.getRecorder().requireRequest();
            assertEquals("REPORT", request.getMethod());
            // With REPORT the context goes in the body, not the URL path
            assertFalse("path should not contain base64 context segment when using REPORT",
                    request.getPath().contains(LDUtil.urlSafeBase64(CONTEXT)));
            assertNotNull("body should contain serialized context", request.getBody());
            assertTrue("body should contain context key",
                    request.getBody().contains("test-context"));

            sync.close();
        }
    }

    // ---- ping event ----

    @Test
    public void pingEventIgnoredWithoutRequestor() throws Exception {
        // When no requestor is configured, a ping event is silently dropped and
        // subsequent events are processed normally.
        String pingEvent = makeEvent("ping", "{}");
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(pingEvent),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            // Default makeSynchronizer uses null requestor
            FDv2StreamingSynchronizer sync = makeSynchronizer(server.getUri());

            Future<FDv2SourceResult> resultFuture = sync.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            // Ping was ignored; changeset from subsequent events is returned
            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());

            sync.close();
        }
    }

    @Test
    public void pingEventWithRequestorTriggersPoll() throws Exception {
        // When a requestor is configured, a ping event causes an immediate poll and
        // queues the poll result as the next() return value.
        String pingEvent = makeEvent("ping", "{}");

        try (HttpServer pollServer = HttpServer.start(Handlers.bodyJson(VALID_POLL_RESPONSE_JSON));
             HttpServer streamServer = HttpServer.start(Handlers.all(
                     Handlers.SSE.start(),
                     Handlers.SSE.event(pingEvent),
                     Handlers.SSE.leaveOpen()))) {

            DefaultFDv2Requestor requestor = new DefaultFDv2Requestor(
                    CONTEXT, pollServer.getUri(),
                    POLL_GET_PATH, POLL_REPORT_PATH,
                    httpProperties(), false, false, null, LOGGER);

            FDv2StreamingSynchronizer sync = new FDv2StreamingSynchronizer(
                    CONTEXT, EMPTY_SELECTOR_SOURCE, streamServer.getUri(), STREAM_PATH,
                    requestor, 100, false, false,
                    httpProperties(), executor, LOGGER, null);

            try {
                Future<FDv2SourceResult> resultFuture = sync.next();
                FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

                // Result comes from the poll triggered by the ping
                assertNotNull(result);
                assertEquals(SourceResultType.CHANGE_SET, result.getResultType());

                // Poll server received exactly one request
                assertEquals(1, pollServer.getRecorder().count());
            } finally {
                sync.close();
            }
        }
    }

    // ---- requestor lifecycle ----

    @Test
    public void closeAlsoClosesRequestor() throws IOException {
        // When a requestor is supplied at construction, close() must close it too.
        AtomicBoolean requestorClosed = new AtomicBoolean(false);

        FDv2Requestor trackingRequestor = new FDv2Requestor() {
            @Override
            public Future<FDv2Requestor.FDv2PayloadResponse> poll(Selector selector) {
                throw new UnsupportedOperationException("not needed for this test");
            }

            @Override
            public void close() {
                requestorClosed.set(true);
            }
        };

        FDv2StreamingSynchronizer sync = new FDv2StreamingSynchronizer(
                CONTEXT, EMPTY_SELECTOR_SOURCE, URI.create("http://localhost:9999/"), STREAM_PATH,
                trackingRequestor, 100, false, false,
                httpProperties(), executor, LOGGER, null);

        assertFalse(requestorClosed.get());
        sync.close();
        assertTrue("requestor.close() should be called when synchronizer is closed",
                requestorClosed.get());
    }

    // ---- diagnostic: non-recoverable HTTP ----

    @Test
    public void nonRecoverableHttpErrorRecordsDiagnostic() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();

        try (HttpServer server = HttpServer.start(Handlers.status(401))) {
            FDv2StreamingSynchronizer sync = makeSynchronizer(
                    server.getUri(), EMPTY_SELECTOR_SOURCE, diagnosticStore);

            FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());

            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();
            LDValue streamInits = event.get("streamInits");
            assertEquals(1, streamInits.size());
            assertTrue("Non-recoverable HTTP error should record a failed stream init",
                    streamInits.get(0).get("failed").booleanValue());

            sync.close();
        }
    }

    private static String makeEvent(String type, String data) {
        return "event: " + type + "\ndata: " + data;
    }
}
