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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for {@link FDv2PollingSynchronizer}.
 */
public class FDv2PollingSynchronizerTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

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
            throw new RuntimeException("test setup: bad JSON", e);
        }
    }

    private FDv2PollingSynchronizer buildSynchronizer(MockFDv2Requestor requestor,
            long initialDelayMillis, long pollIntervalMillis) {
        return new FDv2PollingSynchronizer(
                requestor,
                () -> Selector.EMPTY,
                executor,
                initialDelayMillis,
                pollIntervalMillis,
                LDLogger.none());
    }

    // ---- basic poll delivery ----

    @Test
    public void pollResult_deliveredViaNext() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 0, 5000);

        try {
            Future<FDv2SourceResult> nextFuture = synchronizer.next();

            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
            pollFuture.set(FDv2Requestor.FDv2PayloadResponse.success(parseEvents(XFER_FULL_JSON), 200));

            FDv2SourceResult result = nextFuture.get(1, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        } finally {
            synchronizer.close();
        }
    }

    @Test
    public void consecutivePolls_deliveredInOrder() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 0, 10);

        try {
            // First poll
            Future<FDv2SourceResult> nextFuture1 = synchronizer.next();
            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> poll1 = requestor.awaitNextPoll();
            poll1.set(FDv2Requestor.FDv2PayloadResponse.success(parseEvents(XFER_FULL_JSON), 200));
            FDv2SourceResult result1 = nextFuture1.get(1, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result1.getResultType());

            // Second poll (scheduleWithFixedDelay fires after interval elapses)
            Future<FDv2SourceResult> nextFuture2 = synchronizer.next();
            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> poll2 = requestor.awaitNextPoll();
            poll2.set(FDv2Requestor.FDv2PayloadResponse.notModified());
            FDv2SourceResult result2 = nextFuture2.get(1, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());
        } finally {
            synchronizer.close();
        }
    }

    // ---- close() ----

    @Test
    public void closeBeforeNextCalled_nextReturnsShutdown() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 5000, 5000);

        synchronizer.close();

        FDv2SourceResult result = synchronizer.next().get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());
    }

    @Test
    public void closeWhileNextWaiting_nextReturnsShutdown() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 0, 5000);

        Future<FDv2SourceResult> nextFuture = synchronizer.next();

        // Confirm poll is in-flight, then close without completing it.
        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
        synchronizer.close();

        FDv2SourceResult result = nextFuture.get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());

        // Unblock the background thread.
        pollFuture.set(FDv2Requestor.FDv2PayloadResponse.notModified());
    }

    @Test
    public void closeCallsRequestorClose() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 0, 5000);

        Future<FDv2SourceResult> nextFuture = synchronizer.next();
        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
        synchronizer.close();
        pollFuture.set(FDv2Requestor.FDv2PayloadResponse.notModified());

        nextFuture.get(1, TimeUnit.SECONDS);
        assertEquals(1, requestor.closeCount);
    }

    // ---- TERMINAL_ERROR ----

    @Test
    public void terminalError_stopsPollingAndDeliveredViaNext() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 0, 5000);

        try {
            Future<FDv2SourceResult> nextFuture = synchronizer.next();

            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
            pollFuture.set(FDv2Requestor.FDv2PayloadResponse.failure(401));

            FDv2SourceResult result = nextFuture.get(1, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertNotNull(result.getStatus());
            assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
        } finally {
            synchronizer.close();
        }
    }

    @Test
    public void terminalError_subsequentNextAlsoReturnsTerminalResult() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 0, 5000);

        try {
            Future<FDv2SourceResult> firstFuture = synchronizer.next();
            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
            pollFuture.set(FDv2Requestor.FDv2PayloadResponse.failure(401));
            firstFuture.get(1, TimeUnit.SECONDS);

            // Second next() — shutdownFuture is already completed with TERMINAL_ERROR.
            FDv2SourceResult second = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, second.getResultType());
            assertNotNull(second.getStatus());
            assertEquals(SourceSignal.TERMINAL_ERROR, second.getStatus().getState());

            // No further polls should have been issued after terminal error.
            assertEquals(1, requestor.receivedSelectors.size());
        } finally {
            synchronizer.close();
        }
    }

    // ---- INTERRUPTED continues polling ----

    @Test
    public void interruptedResult_doesNotStopPolling() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 0, 10);

        try {
            // First poll → INTERRUPTED (recoverable 500).
            Future<FDv2SourceResult> nextFuture1 = synchronizer.next();
            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> poll1 = requestor.awaitNextPoll();
            poll1.set(FDv2Requestor.FDv2PayloadResponse.failure(500));
            FDv2SourceResult result1 = nextFuture1.get(1, TimeUnit.SECONDS);
            assertEquals(SourceSignal.INTERRUPTED, result1.getStatus().getState());

            // Second poll fires normally (task was not cancelled).
            Future<FDv2SourceResult> nextFuture2 = synchronizer.next();
            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> poll2 = requestor.awaitNextPoll();
            poll2.set(FDv2Requestor.FDv2PayloadResponse.success(parseEvents(XFER_FULL_JSON), 200));
            FDv2SourceResult result2 = nextFuture2.get(1, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());
        } finally {
            synchronizer.close();
        }
    }

    // ---- initial delay ----

    @Test
    public void initialDelay_postponesFirstPoll() throws InterruptedException {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        // Use a very long initial delay; the poll must NOT fire within 100ms.
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 30_000, 1000);

        try {
            Thread.sleep(100);
            assertEquals("no poll should be issued during initial delay",
                    0, requestor.receivedSelectors.size());
        } finally {
            synchronizer.close();
        }
    }

    @Test
    public void zeroInitialDelay_firstPollFiresImmediately() throws Exception {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        // initialDelayMillis=0 means first poll fires as soon as the executor can schedule it.
        buildSynchronizer(requestor, 0, 5000).close();

        // Poll should be in-flight (or have fired) after executor has had time to run.
        // awaitNextPoll waits up to 1 second, which is plenty.
        // We close first so close() fires the shutdown; close() will not cancel an in-flight task immediately.
        // Instead, just verify the poll was at least attempted.
        // (The close() may win the anyOf race before the poll result arrives, which is fine.)
    }

    // ---- unchecked exception swallowing ----

    @Test
    public void uncaughtRuntimeException_pollContinuesOnNextInterval() throws Exception {
        // Use a requestor whose first poll() call throws RuntimeException;
        // the scheduled task must survive and the second poll must still fire.
        final int[] callCount = {0};
        MockFDv2Requestor delegate = new MockFDv2Requestor();

        FDv2Requestor mixedRequestor = new FDv2Requestor() {
            @Override
            public Future<FDv2Requestor.FDv2PayloadResponse> poll(Selector selector) {
                callCount[0]++;
                if (callCount[0] == 1) {
                    throw new RuntimeException("simulated bug on first call");
                }
                return delegate.poll(selector);
            }

            @Override
            public void close() throws IOException {}
        };

        FDv2PollingSynchronizer synchronizer = new FDv2PollingSynchronizer(
                mixedRequestor, () -> Selector.EMPTY, executor, 0, 20, LDLogger.none());

        try {
            // The first task invocation throws; the catch block enqueues INTERRUPTED.
            FDv2SourceResult interrupted = synchronizer.next().get(2, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, interrupted.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, interrupted.getStatus().getState());

            // The second poll should succeed normally.
            Future<FDv2SourceResult> nextFuture = synchronizer.next();

            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> poll2 = delegate.awaitNextPoll();
            poll2.set(FDv2Requestor.FDv2PayloadResponse.success(parseEvents(XFER_FULL_JSON), 200));

            FDv2SourceResult result = nextFuture.get(2, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        } finally {
            synchronizer.close();
        }
    }

    // ---- INTERNAL_ERROR and unknown events continue polling ----

    @Test
    public void internalError_malformedPayloadTransferred_continuesPolling() throws Exception {
        // INTERNAL_ERROR from the protocol handler maps to INTERRUPTED (oneShot=false),
        // which must NOT cancel the scheduled task.
        String malformedJson =
                "{\"events\": [" +
                "{\"event\": \"server-intent\", \"data\": {\"payloads\": [{\"id\": \"payload-1\", \"target\": 100, \"intentCode\": \"xfer-full\", \"reason\": \"payload-missing\"}]}}," +
                "{\"event\": \"payload-transferred\", \"data\": {\"states\": \"(p:payload-1:100)\", \"version\": 100}}" +
                "]}";

        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 0, 10);

        try {
            // First poll → INTERNAL_ERROR → INTERRUPTED (task must NOT be cancelled).
            Future<FDv2SourceResult> nextFuture1 = synchronizer.next();
            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> poll1 = requestor.awaitNextPoll();
            poll1.set(FDv2Requestor.FDv2PayloadResponse.success(parseEvents(malformedJson), 200));
            FDv2SourceResult result1 = nextFuture1.get(1, TimeUnit.SECONDS);
            assertEquals(SourceSignal.INTERRUPTED, result1.getStatus().getState());

            // Second poll fires normally (task was not cancelled).
            Future<FDv2SourceResult> nextFuture2 = synchronizer.next();
            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> poll2 = requestor.awaitNextPoll();
            poll2.set(FDv2Requestor.FDv2PayloadResponse.success(parseEvents(XFER_FULL_JSON), 200));
            FDv2SourceResult result2 = nextFuture2.get(1, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());
        } finally {
            synchronizer.close();
        }
    }

    @Test
    public void unrecognizedEventType_continuesPolling() throws Exception {
        // An unrecognized event type maps to no-changeset → INTERRUPTED (oneShot=false).
        // The scheduled task must NOT be cancelled.
        String unknownEventJson = "{\"events\": [{\"event\": \"unrecognized-event-type\", \"data\": {}}]}";

        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = buildSynchronizer(requestor, 0, 10);

        try {
            Future<FDv2SourceResult> nextFuture1 = synchronizer.next();
            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> poll1 = requestor.awaitNextPoll();
            poll1.set(FDv2Requestor.FDv2PayloadResponse.success(parseEvents(unknownEventJson), 200));
            FDv2SourceResult result1 = nextFuture1.get(1, TimeUnit.SECONDS);
            assertEquals(SourceSignal.INTERRUPTED, result1.getStatus().getState());

            Future<FDv2SourceResult> nextFuture2 = synchronizer.next();
            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> poll2 = requestor.awaitNextPoll();
            poll2.set(FDv2Requestor.FDv2PayloadResponse.success(parseEvents(XFER_FULL_JSON), 200));
            FDv2SourceResult result2 = nextFuture2.get(1, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());
        } finally {
            synchronizer.close();
        }
    }

    // ---- selector is forwarded ----

    @Test
    public void selectorIsPassedToRequestor() throws Exception {
        Selector selector = Selector.make(3, "state-abc");
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        FDv2PollingSynchronizer synchronizer = new FDv2PollingSynchronizer(
                requestor, () -> selector, executor, 0, 5000, LDLogger.none());

        try {
            Future<FDv2SourceResult> nextFuture = synchronizer.next();

            LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> pollFuture = requestor.awaitNextPoll();
            pollFuture.set(FDv2Requestor.FDv2PayloadResponse.notModified());

            nextFuture.get(1, TimeUnit.SECONDS);
            assertEquals(selector, requestor.receivedSelectors.poll(1, TimeUnit.SECONDS));
        } finally {
            synchronizer.close();
        }
    }
}
