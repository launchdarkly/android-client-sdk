package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class StreamingDataSourceTest {

    private static final LDContext CONTEXT = LDContext.create("context-key");
    private static final String MOBILE_KEY = "test-mobile-key";
    private static final String VALID_PUT_JSON = "{\"flag1\":{\"key\":\"flag1\",\"version\":1,\"value\":true}}";

    @Rule
    public Timeout globalTimeout = Timeout.seconds(10);
    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    private final MockComponents.MockDataSourceUpdateSink dataSourceUpdateSink = new MockComponents.MockDataSourceUpdateSink();
    private final MockPlatformState platformState = new MockPlatformState();
    private final IEnvironmentReporter environmentReporter = new EnvironmentReporterBuilder().build();
    private final SimpleTestTaskExecutor taskExecutor = new SimpleTestTaskExecutor();
    private PersistentDataStoreWrapper.PerEnvironmentData perEnvironmentData;

    @Before
    public void before() {
        perEnvironmentData = TestUtil.makeSimplePersistentDataStoreWrapper().perEnvironmentData(MOBILE_KEY);
    }

    private ClientContext makeClientContext(boolean inBackground, Boolean previouslyInBackground) {
        ClientContext baseClientContext = ClientContextImpl.fromConfig(
                new LDConfig.Builder(AutoEnvAttributes.Disabled).build(), "", "", perEnvironmentData, null, CONTEXT,
                logging.logger, platformState, environmentReporter, taskExecutor);
        return ClientContextImpl.forDataSource(
                baseClientContext,
                dataSourceUpdateSink,
                CONTEXT,
                inBackground,
                previouslyInBackground
        );
    }

    // When react to a PING message from the stream,
    // the StreamingDataSource will try to use the fetcher
    // to get flag data, so we need to create a ClientContext (Android runtime state)
    // that has a fetcher
    private ClientContext makeClientContextWithFetcher() {
        ClientContext baseClientContext = ClientContextImpl.fromConfig(
                new LDConfig.Builder(AutoEnvAttributes.Disabled).build(), "", "", perEnvironmentData, makeFeatureFetcher(), CONTEXT,
                logging.logger, platformState, environmentReporter, taskExecutor);
        return ClientContextImpl.forDataSource(
                baseClientContext,
                dataSourceUpdateSink,
                CONTEXT,
                false, //Not in background
                false //Not Previously in background
        );
    }

    private FeatureFetcher makeFeatureFetcher() {
        return new FeatureFetcher() {
            @Override
            public void close() throws IOException {
                // Do nothing
            }

            @Override
            public void fetch(LDContext context, Callback<String> callback) {
                String json = "{" +
                        "\"flag1\":{\"key\":\"flag1\",\"version\":200,\"value\":false}" +
                        "}";
                callback.onSuccess(json);
            }
        };
    }

    private StreamingDataSource makeStreamingDataSource(boolean streamEvenInBackground) {
        ClientContext clientContext = makeClientContextWithFetcher();
        return (StreamingDataSource) Components.streamingDataSource()
                .streamEvenInBackground(streamEvenInBackground)
                .build(clientContext);
    }

    private StreamingDataSource makeStreamingDataSource(
            URI streamBaseUri, boolean evaluationReasons, boolean useReport) {
        return makeStreamingDataSource(
                streamBaseUri, dataSourceUpdateSink, evaluationReasons, useReport);
    }

    private StreamingDataSource makeStreamingDataSource(
            URI streamBaseUri,
            MockComponents.MockDataSourceUpdateSink sink,
            boolean evaluationReasons, boolean useReport) {
        LDConfig.Builder configBuilder = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .serviceEndpoints(Components.serviceEndpoints().streaming(streamBaseUri))
                .evaluationReasons(evaluationReasons);
        if (useReport) {
            configBuilder.http(Components.httpConfiguration().useReport(true));
        }
        ClientContext baseClientContext = ClientContextImpl.fromConfig(
                configBuilder.build(), MOBILE_KEY, "", perEnvironmentData,
                makeFeatureFetcher(), CONTEXT,
                logging.logger, platformState, environmentReporter, taskExecutor);
        ClientContext clientContext = ClientContextImpl.forDataSource(
                baseClientContext, sink, CONTEXT, false, false);
        return (StreamingDataSource) Components.streamingDataSource()
                .initialReconnectDelayMillis(100)
                .build(clientContext);
    }

    private static String makeSseEvent(String type, String data) {
        return "event: " + type + "\ndata: " + data;
    }

    private static class TrackingCallback implements Callback<Boolean> {
        final BlockingQueue<Boolean> successes = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

        @Override
        public void onSuccess(Boolean result) {
            successes.add(result != null ? result : false);
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }

        Boolean awaitSuccess() throws InterruptedException {
            return successes.poll(5, TimeUnit.SECONDS);
        }

        Throwable awaitError() throws InterruptedException {
            return errors.poll(5, TimeUnit.SECONDS);
        }
    }

    // --- Builder tests ---

    @Test
    public void builderCreatesStreamingDataSourceWhenStartingInForeground() {
        ClientContext clientContext = makeClientContext(false, null);
        DataSource ds = Components.streamingDataSource()
                .initialReconnectDelayMillis(999)
                .build(clientContext);

        assertEquals(StreamingDataSource.class, ds.getClass());

        assertEquals(999, ((StreamingDataSource)ds).initialReconnectDelayMillis);
    }

    @Test
    public void builderCreatesPollingDataSourceWhenStartingInBackground() {
        ClientContext clientContext = makeClientContext(true, null);
        DataSource ds = Components.streamingDataSource()
                .backgroundPollIntervalMillis(999999)
                .build(clientContext);

        assertEquals(PollingDataSource.class, ds.getClass());

        assertEquals(999999L, ((PollingDataSource)ds).pollIntervalMillis);

        // no initial delay because the data source is starting for the very first time
        assertEquals(0L, ((PollingDataSource)ds).initialDelayMillis);
    }

    @Test
    public void builderCreatesStreamingDataSourceWhenStartingInBackgroundWithOverride() {
        ClientContext clientContext = makeClientContext(true, null);
        DataSource ds = Components.streamingDataSource()
                .streamEvenInBackground(true)
                .build(clientContext);

        assertEquals(StreamingDataSource.class, ds.getClass());
    }

    // --- handle(): PING ---

    @Test
    public void handlePingMessageBehavior() {
        ClientContext clientContext = makeClientContextWithFetcher();
        StreamingDataSource sds = (StreamingDataSource) Components.streamingDataSource()
                .streamEvenInBackground(true)
                .build(clientContext);

        final Boolean[] callbackInvoked = {false};
        Callback<Boolean> callback = new Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                callbackInvoked[0] = true;
            }

            @Override
            public void onError(Throwable error) {
                // We are testing the callback is getting call, not which callback is called
            }
        };
        sds.handle("ping", null, callback);
        assertTrue(callbackInvoked[0]);
    }

    // --- handle(): PUT ---

    @Test
    public void handlePutWithValidJsonInitsSinkAndReportsSuccess() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        String putJson = "{\"flag1\":{\"key\":\"flag1\",\"version\":1,\"value\":true}}";
        sds.handle("put", putJson, callback);

        Map<String, DataModel.Flag> receivedInit = dataSourceUpdateSink.expectInit();
        assertNotNull(receivedInit);
        assertTrue(receivedInit.containsKey("flag1"));
        assertEquals(LDValue.of(true), receivedInit.get("flag1").getValue());

        Boolean success = callback.awaitSuccess();
        assertNotNull(success);
        assertTrue(success);
    }

    @Test
    public void handlePutWithMultipleFlagsInitsSinkWithAll() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        String putJson = "{" +
                "\"flag1\":{\"key\":\"flag1\",\"version\":1,\"value\":true}," +
                "\"flag2\":{\"key\":\"flag2\",\"version\":2,\"value\":\"hello\"}" +
                "}";
        sds.handle("put", putJson, callback);

        Map<String, DataModel.Flag> receivedInit = dataSourceUpdateSink.expectInit();
        assertEquals(2, receivedInit.size());
        assertTrue(receivedInit.containsKey("flag1"));
        assertTrue(receivedInit.containsKey("flag2"));

        assertNotNull(callback.awaitSuccess());
    }

    @Test
    public void handlePutWithInvalidJsonReportsError() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        sds.handle("put", "NOT VALID JSON {{{", callback);

        Throwable error = callback.awaitError();
        assertNotNull(error);
        assertTrue(error instanceof LDFailure);
        assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ((LDFailure) error).getFailureType());

        assertTrue(dataSourceUpdateSink.inits.isEmpty());
    }

    @Test
    public void handlePutWithEmptyObjectInitsSinkWithNoFlags() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        sds.handle("put", "{}", callback);

        Map<String, DataModel.Flag> receivedInit = dataSourceUpdateSink.expectInit();
        assertNotNull(receivedInit);
        assertTrue(receivedInit.isEmpty());

        assertNotNull(callback.awaitSuccess());
    }

    // --- handle(): PATCH ---

    @Test
    public void handlePatchWithValidFlagUpsertsSink() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        String patchJson = "{\"key\":\"flag1\",\"version\":2,\"value\":\"updated\"}";
        sds.handle("patch", patchJson, callback);

        DataModel.Flag upserted = dataSourceUpdateSink.expectUpsert("flag1");
        assertEquals(2, upserted.getVersion());
        assertEquals(LDValue.of("updated"), upserted.getValue());

        assertNotNull(callback.awaitSuccess());
    }

    @Test
    public void handlePatchWithInvalidJsonReportsError() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        sds.handle("patch", "NOT VALID JSON", callback);

        Throwable error = callback.awaitError();
        assertNotNull(error);
        assertTrue(error instanceof LDFailure);
        assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ((LDFailure) error).getFailureType());

        assertTrue(dataSourceUpdateSink.upserts.isEmpty());
    }

    // --- handle(): DELETE ---

    @Test
    public void handleDeleteUpsertsDeletedPlaceholder() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        String deleteJson = "{\"key\":\"flag1\",\"version\":3}";
        sds.handle("delete", deleteJson, callback);

        DataModel.Flag upserted = dataSourceUpdateSink.expectUpsert("flag1");
        assertEquals(3, upserted.getVersion());
        assertTrue(upserted.isDeleted());

        assertNotNull(callback.awaitSuccess());
    }

    @Test
    public void handleDeleteWithInvalidJsonReportsError() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        sds.handle("delete", "NOT VALID JSON", callback);

        Throwable error = callback.awaitError();
        assertNotNull(error);
        assertTrue(error instanceof LDFailure);
        assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ((LDFailure) error).getFailureType());

        assertTrue(dataSourceUpdateSink.upserts.isEmpty());
    }

    // --- handle(): unknown event ---

    @Test
    public void handleUnknownEventTypeReportsError() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        sds.handle("something-unknown", "{}", callback);

        Throwable error = callback.awaitError();
        assertNotNull(error);
        assertTrue(error instanceof LDFailure);
        assertEquals(LDFailure.FailureType.UNEXPECTED_STREAM_ELEMENT_TYPE, ((LDFailure) error).getFailureType());
    }

    // --- handle(): case insensitivity ---

    @Test
    public void handleEventNameIsCaseInsensitive() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        String putJson = "{\"flag1\":{\"key\":\"flag1\",\"version\":1,\"value\":true}}";

        TrackingCallback uppercaseCallback = new TrackingCallback();
        sds.handle("PUT", putJson, uppercaseCallback);
        assertNotNull(dataSourceUpdateSink.expectInit());
        assertNotNull(uppercaseCallback.awaitSuccess());

        TrackingCallback mixedCaseCallback = new TrackingCallback();
        sds.handle("Put", putJson, mixedCaseCallback);
        assertNotNull(dataSourceUpdateSink.expectInit());
        assertNotNull(mixedCaseCallback.awaitSuccess());
    }

    @Test
    public void handlePatchEventNameIsCaseInsensitive() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        String patchJson = "{\"key\":\"flag1\",\"version\":2,\"value\":false}";
        sds.handle("PATCH", patchJson, callback);

        dataSourceUpdateSink.expectUpsert("flag1");
        assertNotNull(callback.awaitSuccess());
    }

    @Test
    public void handleDeleteEventNameIsCaseInsensitive() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        String deleteJson = "{\"key\":\"flag1\",\"version\":3}";
        sds.handle("DELETE", deleteJson, callback);

        dataSourceUpdateSink.expectUpsert("flag1");
        assertNotNull(callback.awaitSuccess());
    }

    // --- needsRefresh ---

    @Test
    public void needsRefreshReturnsTrueWhenContextChanges() {
        StreamingDataSource sds = makeStreamingDataSource(false);
        LDContext differentContext = LDContext.create("different-key");

        assertTrue(sds.needsRefresh(false, differentContext));
    }

    @Test
    public void needsRefreshReturnsFalseForSameContextInForeground() {
        StreamingDataSource sds = makeStreamingDataSource(false);

        assertFalse(sds.needsRefresh(false, CONTEXT));
    }

    @Test
    public void needsRefreshReturnsTrueWhenMovingToBackgroundWithoutStreamEvenInBackground() {
        StreamingDataSource sds = makeStreamingDataSource(false);

        assertTrue(sds.needsRefresh(true, CONTEXT));
    }

    @Test
    public void needsRefreshReturnsFalseWhenMovingToBackgroundWithStreamEvenInBackground() {
        StreamingDataSource sds = makeStreamingDataSource(true);

        assertFalse(sds.needsRefresh(true, CONTEXT));
    }

    @Test
    public void needsRefreshReturnsTrueWhenContextChangesEvenIfStreamingInBackground() {
        StreamingDataSource sds = makeStreamingDataSource(true);
        LDContext differentContext = LDContext.create("different-key");

        assertTrue(sds.needsRefresh(true, differentContext));
    }

    // --- handle(): null deserialization edge cases ---

    @Test
    public void handlePatchWithNullDeserializationDoesNotCallCallback() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        sds.handle("patch", "null", callback);

        assertNull(callback.successes.poll(200, TimeUnit.MILLISECONDS));
        assertNull(callback.errors.poll(200, TimeUnit.MILLISECONDS));
        assertTrue(dataSourceUpdateSink.upserts.isEmpty());
    }

    @Test
    public void handleDeleteWithNullDeserializationDoesNotCallCallback() throws InterruptedException {
        StreamingDataSource sds = makeStreamingDataSource(false);
        TrackingCallback callback = new TrackingCallback();

        sds.handle("delete", "null", callback);

        assertNull(callback.successes.poll(200, TimeUnit.MILLISECONDS));
        assertNull(callback.errors.poll(200, TimeUnit.MILLISECONDS));
        assertTrue(dataSourceUpdateSink.upserts.isEmpty());
    }

    // --- start(): URI construction verified via HttpServer ---

    @Test
    public void startSendsRequestWithBase64ContextInPath() throws Exception {
        String putEvent = makeSseEvent("put", VALID_PUT_JSON);

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(putEvent),
                Handlers.SSE.leaveOpen()))) {

            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), false, false);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            assertNotNull(callback.awaitSuccess());

            RequestInfo request = server.getRecorder().requireRequest();
            String expectedContextSegment = LDUtil.urlSafeBase64(CONTEXT);
            assertTrue("path should contain base64-encoded context: " + request.getPath(),
                    request.getPath().contains(expectedContextSegment));
            assertEquals("GET", request.getMethod());
        }
    }

    @Test
    public void startSendsRequestWithoutContextWhenUsingReport() throws Exception {
        String putEvent = makeSseEvent("put", VALID_PUT_JSON);

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(putEvent),
                Handlers.SSE.leaveOpen()))) {

            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), false, true);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            assertNotNull(callback.awaitSuccess());

            RequestInfo request = server.getRecorder().requireRequest();
            String base64Context = LDUtil.urlSafeBase64(CONTEXT);
            assertFalse("path should not contain base64 context when using REPORT: " + request.getPath(),
                    request.getPath().contains(base64Context));
            assertEquals("REPORT", request.getMethod());
            assertNotNull("body should contain serialized context", request.getBody());
            assertTrue("body should contain context key",
                    request.getBody().contains("context-key"));
        }
    }

    @Test
    public void startSendsRequestWithReasonsWhenEnabled() throws Exception {
        String putEvent = makeSseEvent("put", VALID_PUT_JSON);

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(putEvent),
                Handlers.SSE.leaveOpen()))) {

            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), true, false);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            assertNotNull(callback.awaitSuccess());

            RequestInfo request = server.getRecorder().requireRequest();
            assertTrue("query should contain withReasons=true",
                    request.getQuery() != null && request.getQuery().contains("withReasons=true"));
        }
    }

    @Test
    public void startSendsRequestWithoutReasonsWhenDisabled() throws Exception {
        String putEvent = makeSseEvent("put", VALID_PUT_JSON);

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(putEvent),
                Handlers.SSE.leaveOpen()))) {

            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), false, false);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            assertNotNull(callback.awaitSuccess());

            RequestInfo request = server.getRecorder().requireRequest();
            assertTrue("query should not contain withReasons",
                    request.getQuery() == null || !request.getQuery().contains("withReasons"));
        }
    }

    @Test
    public void startSendsRequestWithReportAndReasons() throws Exception {
        String putEvent = makeSseEvent("put", VALID_PUT_JSON);

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(putEvent),
                Handlers.SSE.leaveOpen()))) {

            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), true, true);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            assertNotNull(callback.awaitSuccess());

            RequestInfo request = server.getRecorder().requireRequest();
            assertEquals("REPORT", request.getMethod());
            String base64Context = LDUtil.urlSafeBase64(CONTEXT);
            assertFalse("path should not contain base64 context when using REPORT",
                    request.getPath().contains(base64Context));
            assertTrue("query should contain withReasons=true",
                    request.getQuery() != null && request.getQuery().contains("withReasons=true"));
        }
    }

    // --- start(): error handling verified via HttpServer ---

    @Test
    public void startWithHttp401ShutsDownSink() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(401))) {
            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), false, false);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            Throwable error = callback.awaitError();
            assertNotNull(error);
            assertTrue(error instanceof LDInvalidResponseCodeFailure);
            LDInvalidResponseCodeFailure failure = (LDInvalidResponseCodeFailure) error;
            assertEquals(401, failure.getResponseCode());
            assertFalse(failure.isRetryable());
            assertTrue(dataSourceUpdateSink.shutDownCalled);
        }
    }

    @Test
    public void startWithHttp403ReportsNonRetriableWithoutShutDown() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(403))) {
            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), false, false);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            Throwable error = callback.awaitError();
            assertNotNull(error);
            assertTrue(error instanceof LDInvalidResponseCodeFailure);
            LDInvalidResponseCodeFailure failure = (LDInvalidResponseCodeFailure) error;
            assertEquals(403, failure.getResponseCode());
            assertFalse(failure.isRetryable());
            assertFalse(dataSourceUpdateSink.shutDownCalled);
        }
    }

    @Test
    public void startWithHttp500ReportsRetriableError() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(500))) {
            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), false, false);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            Throwable error = callback.awaitError();
            assertNotNull(error);
            assertTrue(error instanceof LDInvalidResponseCodeFailure);
            LDInvalidResponseCodeFailure failure = (LDInvalidResponseCodeFailure) error;
            assertEquals(500, failure.getResponseCode());
            assertTrue(failure.isRetryable());
            assertFalse(dataSourceUpdateSink.shutDownCalled);
        }
    }

    @Test
    public void startWithNetworkErrorReportsNetworkFailure() throws Exception {
        // Connect to an address where nothing is listening
        StreamingDataSource sds = makeStreamingDataSource(
                URI.create("http://localhost:1"), false, false);
        TrackingCallback callback = new TrackingCallback();
        sds.start(callback);

        Throwable error = callback.awaitError();
        assertNotNull(error);
        assertTrue(error instanceof LDFailure);
        assertFalse(error instanceof LDInvalidResponseCodeFailure);
        assertEquals(LDFailure.FailureType.NETWORK_FAILURE, ((LDFailure) error).getFailureType());
        assertFalse(dataSourceUpdateSink.shutDownCalled);
    }

    @Test
    public void startWithHttp401PreventsSubsequentStart() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(401))) {
            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), false, false);
            TrackingCallback callback1 = new TrackingCallback();
            sds.start(callback1);

            assertNotNull(callback1.awaitError());

            // Second start should be a no-op due to connection401Error flag
            TrackingCallback callback2 = new TrackingCallback();
            sds.start(callback2);

            assertNull("Second start should not produce a callback",
                    callback2.errors.poll(500, TimeUnit.MILLISECONDS));
            assertNull(callback2.successes.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    // --- start(): SSE event processing via HttpServer ---

    @Test
    public void startReceivesPutEventAndInitsSink() throws Exception {
        String putEvent = makeSseEvent("put", VALID_PUT_JSON);

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(putEvent),
                Handlers.SSE.leaveOpen()))) {

            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), false, false);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            Boolean success = callback.awaitSuccess();
            assertNotNull(success);
            assertTrue(success);

            Map<String, DataModel.Flag> receivedInit = dataSourceUpdateSink.expectInit();
            assertNotNull(receivedInit);
            assertTrue(receivedInit.containsKey("flag1"));
        }
    }

    @Test
    public void startReceivesPatchEventAndUpsertsSink() throws Exception {
        String putEvent = makeSseEvent("put", VALID_PUT_JSON);
        String patchEvent = makeSseEvent("patch", "{\"key\":\"flag1\",\"version\":2,\"value\":\"updated\"}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(putEvent),
                Handlers.SSE.event(patchEvent),
                Handlers.SSE.leaveOpen()))) {

            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), false, false);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            assertNotNull(callback.awaitSuccess());
            dataSourceUpdateSink.expectInit();

            DataModel.Flag upserted = dataSourceUpdateSink.expectUpsert("flag1");
            assertEquals(2, upserted.getVersion());
            assertEquals(LDValue.of("updated"), upserted.getValue());
        }
    }

    @Test
    public void startReceivesDeleteEventAndUpsertsDeletedPlaceholder() throws Exception {
        String putEvent = makeSseEvent("put", VALID_PUT_JSON);
        String deleteEvent = makeSseEvent("delete", "{\"key\":\"flag1\",\"version\":3}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(putEvent),
                Handlers.SSE.event(deleteEvent),
                Handlers.SSE.leaveOpen()))) {

            StreamingDataSource sds = makeStreamingDataSource(
                    server.getUri(), false, false);
            TrackingCallback callback = new TrackingCallback();
            sds.start(callback);

            assertNotNull(callback.awaitSuccess());
            dataSourceUpdateSink.expectInit();

            DataModel.Flag upserted = dataSourceUpdateSink.expectUpsert("flag1");
            assertEquals(3, upserted.getVersion());
            assertTrue(upserted.isDeleted());
        }
    }
}
