package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.json.SerializationException;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for {@link FDv2PollingInitializer}.
 */
public class FDv2PollingInitializerTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    // ---- helpers ----

    private static final String XFER_FULL_JSON =
            "{\"events\": [" +
            "{\"event\": \"server-intent\", \"data\": {\"payloads\": [{\"id\": \"payload-1\", \"target\": 100, \"intentCode\": \"xfer-full\", \"reason\": \"payload-missing\"}]}}," +
            "{\"event\": \"payload-transferred\", \"data\": {\"state\": \"(p:payload-1:100)\", \"version\": 100}}" +
            "]}";

    private static List<FDv2Event> parseEvents(String json) {
        try {
            return FDv2Event.parseEventsArray(json);
        } catch (SerializationException e) {
            throw new RuntimeException("test setup failed", e);
        }
    }

    private FDv2PollingInitializer buildInitializer(MockFDv2Requestor requestor) {
        return new FDv2PollingInitializer(requestor, () -> Selector.EMPTY, executor, LDLogger.none());
    }

    // ---- successful poll ----

    @Test
    public void successfulPoll_futureCompletesWithChangeSet() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingInitializer initializer = buildInitializer(requestor);

        Future<FDv2SourceResult> future = initializer.run();

        // Unblock the in-flight poll with a successful response.
        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
        pollFuture.set(FDv2Requestor.FDv2PayloadResponse.success(parseEvents(XFER_FULL_JSON), 200, false));

        FDv2SourceResult result = future.get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertNotNull(result.getChangeSet());
    }

    // ---- HTTP and network errors (oneShot=true → always TERMINAL_ERROR) ----

    @Test
    public void recoverableHttpError_futureCompletesWithTerminalError() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingInitializer initializer = buildInitializer(requestor);

        Future<FDv2SourceResult> future = initializer.run();

        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
        pollFuture.set(FDv2Requestor.FDv2PayloadResponse.failure(500, false));

        FDv2SourceResult result = future.get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    @Test
    public void nonRecoverableHttpError_futureCompletesWithTerminalError() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingInitializer initializer = buildInitializer(requestor);

        Future<FDv2SourceResult> future = initializer.run();

        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
        pollFuture.set(FDv2Requestor.FDv2PayloadResponse.failure(401, false));

        FDv2SourceResult result = future.get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    @Test
    public void networkError_futureCompletesWithTerminalError() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingInitializer initializer = buildInitializer(requestor);

        Future<FDv2SourceResult> future = initializer.run();

        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
        pollFuture.setException(new IOException("network error"));

        FDv2SourceResult result = future.get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    // ---- close() behaviour ----

    @Test
    public void closeBeforePollCompletes_futureCompletesWithShutdown() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingInitializer initializer = buildInitializer(requestor);

        Future<FDv2SourceResult> future = initializer.run();

        // Wait for the poll to be in-flight, but don't resolve it.
        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();

        // Close the initializer; shutdown future wins the anyOf race.
        initializer.close();

        FDv2SourceResult result = future.get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());

        // Allow the background thread to unblock cleanly.
        pollFuture.set(FDv2Requestor.FDv2PayloadResponse.notModified(false));
    }

    @Test
    public void closeAfterPollCompletes_doesNotThrow() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingInitializer initializer = buildInitializer(requestor);

        Future<FDv2SourceResult> future = initializer.run();

        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
        pollFuture.set(FDv2Requestor.FDv2PayloadResponse.notModified(false));

        // Wait for poll to complete, then close — should not throw.
        future.get(1, TimeUnit.SECONDS);
        initializer.close();
    }

    @Test
    public void closeBeforeRunIsCalled_runReturnsFutureCompletingWithShutdown() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingInitializer initializer = buildInitializer(requestor);

        initializer.close();
        Future<FDv2SourceResult> future = initializer.run();

        FDv2SourceResult result = future.get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());
    }

    // ---- unchecked exception handling ----

    @Test
    public void uncaughtRuntimeException_futureCompletesWithTerminalError() throws Exception {
        // Requestor's poll() throws an unexpected RuntimeException rather than failing the future.
        FDv2Requestor throwingRequestor = new FDv2Requestor() {
            @Override
            public Future<FDv2Requestor.FDv2PayloadResponse> poll(Selector selector) {
                throw new RuntimeException("unexpected bug");
            }

            @Override
            public void close() throws IOException {}
        };

        FDv2PollingInitializer initializer = new FDv2PollingInitializer(
                throwingRequestor, () -> Selector.EMPTY, executor, LDLogger.none());

        Future<FDv2SourceResult> future = initializer.run();

        FDv2SourceResult result = future.get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    // ---- single poll only ----

    @Test
    public void onlyOnePollIsIssued() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingInitializer initializer = buildInitializer(requestor);

        Future<FDv2SourceResult> future = initializer.run();

        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
        pollFuture.set(FDv2Requestor.FDv2PayloadResponse.success(parseEvents(XFER_FULL_JSON), 200, false));

        future.get(1, TimeUnit.SECONDS);

        // The result queue should have no further pending polls.
        assertEquals(0, requestor.receivedSelectors.size() - 1 /* minus the one we already consumed */);
        assertEquals(1, requestor.receivedSelectors.size());
    }

    // ---- requestor is closed ----

    @Test
    public void closeCallsRequestorClose() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingInitializer initializer = buildInitializer(requestor);

        initializer.run();

        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
        initializer.close();
        pollFuture.set(FDv2Requestor.FDv2PayloadResponse.notModified(false));

        assertEquals(1, requestor.closeCount);
    }
}
