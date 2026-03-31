package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link FDv1PollingSynchronizer}.
 */
public class FDv1PollingSynchronizerTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(10);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private static final LDContext CONTEXT = LDContext.create("test-context-key");
    private static final LDLogger LOGGER = LDLogger.none();

    private static final String VALID_FDV1_JSON =
            "{\"flag1\":{\"key\":\"flag1\",\"version\":1,\"value\":true}," +
            "\"flag2\":{\"key\":\"flag2\",\"version\":2,\"value\":false}}";

    private static final String SINGLE_FLAG_JSON =
            "{\"flag1\":{\"key\":\"flag1\",\"version\":1,\"value\":true}}";

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    private static HttpProperties httpProperties() {
        return new HttpProperties(
                10_000,
                new HashMap<>(),
                null, null, null, null,
                10_000,
                null, null);
    }

    private FDv1PollingSynchronizer makeSynchronizer(HttpServer server) {
        return makeSynchronizer(server, 0, 60_000, false, false, LOGGER);
    }

    private FDv1PollingSynchronizer makeSynchronizer(HttpServer server, long initialDelay, long pollInterval) {
        return makeSynchronizer(server, initialDelay, pollInterval, false, false, LOGGER);
    }

    private FDv1PollingSynchronizer makeSynchronizer(
            HttpServer server, long initialDelay, long pollInterval,
            boolean useReport, boolean evaluationReasons, LDLogger logger) {
        return new FDv1PollingSynchronizer(
                CONTEXT,
                server.getUri(),
                httpProperties(),
                useReport,
                evaluationReasons,
                executor,
                initialDelay,
                pollInterval,
                logger);
    }

    @Test
    public void successfulPollReturnsChangeSet() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(VALID_FDV1_JSON))) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server);
            try {
                Future<FDv2SourceResult> future = sync.next();
                FDv2SourceResult result = future.get(5, TimeUnit.SECONDS);

                assertNotNull(result);
                assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
                assertNotNull(result.getChangeSet());
                assertEquals(ChangeSetType.Full, result.getChangeSet().getType());
                assertNotNull(result.getChangeSet().getData());
                assertEquals(2, result.getChangeSet().getData().size());
                assertTrue(result.getChangeSet().getData().containsKey("flag1"));
                assertTrue(result.getChangeSet().getData().containsKey("flag2"));
                assertFalse(result.isFdv1Fallback());
            } finally {
                sync.close();
            }
        }
    }

    @Test
    public void nonRecoverableHttpErrorReturnsTerminalError() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(401))) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server);
            try {
                FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);

                assertEquals(SourceResultType.STATUS, result.getResultType());
                assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
            } finally {
                sync.close();
            }
        }
    }

    @Test
    public void recoverableHttpErrorReturnsInterrupted() throws Exception {
        Handler sequence = Handlers.sequential(
                Handlers.status(500),
                Handlers.bodyJson(SINGLE_FLAG_JSON));

        try (HttpServer server = HttpServer.start(sequence)) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server, 0, 100);
            try {
                FDv2SourceResult result1 = sync.next().get(5, TimeUnit.SECONDS);
                assertEquals(SourceResultType.STATUS, result1.getResultType());
                assertEquals(SourceSignal.INTERRUPTED, result1.getStatus().getState());

                FDv2SourceResult result2 = sync.next().get(5, TimeUnit.SECONDS);
                assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());
                assertNotNull(result2.getChangeSet());
                assertEquals(1, result2.getChangeSet().getData().size());
            } finally {
                sync.close();
            }
        }
    }

    @Test
    public void closeReturnsShutdown() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(VALID_FDV1_JSON))) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server, 60_000, 60_000);
            sync.close();

            FDv2SourceResult result = sync.next().get(1, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());
        }
    }

    @Test
    public void invalidJsonReturnsInterrupted() throws Exception {
        Handler sequence = Handlers.sequential(
                Handlers.bodyJson("not valid json"),
                Handlers.bodyJson(SINGLE_FLAG_JSON));

        try (HttpServer server = HttpServer.start(sequence)) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server, 0, 100);
            try {
                FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);
                assertEquals(SourceResultType.STATUS, result.getResultType());
                assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
            } finally {
                sync.close();
            }
        }
    }

    @Test
    public void emptyResponseReturnsChangeSetWithNoFlags() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson("{}"))) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server);
            try {
                FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);

                assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
                assertNotNull(result.getChangeSet());
                assertEquals(ChangeSetType.Full, result.getChangeSet().getType());
                assertTrue(result.getChangeSet().getData().isEmpty());
            } finally {
                sync.close();
            }
        }
    }

    // ---- Request path and method verification ----

    @Test
    public void getRequestUsesCorrectPath() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(SINGLE_FLAG_JSON))) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server);
            try {
                sync.next().get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals("GET", req.getMethod());
                String expectedBase64 = LDUtil.urlSafeBase64(CONTEXT);
                String expectedPath = StandardEndpoints.POLLING_REQUEST_GET_BASE_PATH + "/" + expectedBase64;
                assertTrue("path should be " + expectedPath + " but was " + req.getPath(),
                        req.getPath().equals(expectedPath));
            } finally {
                sync.close();
            }
        }
    }

    @Test
    public void reportRequestUsesCorrectPathAndMethod() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(SINGLE_FLAG_JSON))) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server, 0, 60_000, true, false, LOGGER);
            try {
                sync.next().get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals("REPORT", req.getMethod());
                assertTrue("path should start with " + StandardEndpoints.POLLING_REQUEST_REPORT_BASE_PATH,
                        req.getPath().startsWith(StandardEndpoints.POLLING_REQUEST_REPORT_BASE_PATH));
                assertFalse("REPORT path should not contain context segment",
                        req.getPath().startsWith(StandardEndpoints.POLLING_REQUEST_GET_BASE_PATH));
                assertNotNull("body should contain serialized context", req.getBody());
                assertTrue("body should contain context key",
                        req.getBody().contains("test-context-key"));
            } finally {
                sync.close();
            }
        }
    }

    @Test
    public void reportRequestReturnsValidChangeSet() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(VALID_FDV1_JSON))) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server, 0, 60_000, true, false, LOGGER);
            try {
                FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);

                assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
                assertNotNull(result.getChangeSet());
                assertEquals(2, result.getChangeSet().getData().size());
                assertFalse(result.isFdv1Fallback());
            } finally {
                sync.close();
            }
        }
    }

    // ---- withReasons query parameter ----

    @Test
    public void evaluationReasonsAppendsQueryParam() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.bodyJson(SINGLE_FLAG_JSON))) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server, 0, 60_000, false, true, LOGGER);
            try {
                sync.next().get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertTrue("query should contain withReasons=true",
                        req.getQuery() != null && req.getQuery().contains("withReasons=true"));
            } finally {
                sync.close();
            }
        }
    }

    // ---- Network error handling ----

    @Test
    public void networkErrorReturnsInterrupted() throws Exception {
        FDv1PollingSynchronizer sync = new FDv1PollingSynchronizer(
                CONTEXT,
                URI.create("http://localhost:1"),
                httpProperties(),
                false,
                false,
                executor,
                0,
                60_000,
                LOGGER);
        try {
            FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);

            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
        } finally {
            sync.close();
        }
    }

    // ---- Terminal error stops polling ----

    @Test
    public void terminalErrorStopsPolling() throws Exception {
        Handler sequence = Handlers.sequential(
                Handlers.status(401),
                Handlers.bodyJson(SINGLE_FLAG_JSON));

        try (HttpServer server = HttpServer.start(sequence)) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server, 0, 100);
            try {
                FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);
                assertEquals(SourceResultType.STATUS, result.getResultType());
                assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());

                Thread.sleep(500);

                assertEquals("should have made exactly one request before stopping",
                        1, server.getRecorder().count());
            } finally {
                sync.close();
            }
        }
    }

    // ---- Repeated polling ----

    @Test
    public void pollsRepeatAtConfiguredInterval() throws Exception {
        Handler sequence = Handlers.sequential(
                Handlers.bodyJson(SINGLE_FLAG_JSON),
                Handlers.bodyJson(VALID_FDV1_JSON));

        try (HttpServer server = HttpServer.start(sequence)) {
            FDv1PollingSynchronizer sync = makeSynchronizer(server, 0, 200);
            try {
                FDv2SourceResult result1 = sync.next().get(5, TimeUnit.SECONDS);
                assertEquals(SourceResultType.CHANGE_SET, result1.getResultType());
                assertEquals(1, result1.getChangeSet().getData().size());

                FDv2SourceResult result2 = sync.next().get(5, TimeUnit.SECONDS);
                assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());
                assertEquals(2, result2.getChangeSet().getData().size());
            } finally {
                sync.close();
            }
        }
    }
}
