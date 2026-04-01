package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
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

    /**
     * Test double that queues responses for the synchronizer to consume.
     * Mirrors the {@code MockFetcher} pattern used in {@link PollingDataSourceTest}.
     */
    private static class MockFetcher implements FeatureFetcher {
        final BlockingQueue<Object> responses = new LinkedBlockingQueue<>();

        void queueSuccess(String json) {
            responses.add(json);
        }

        void queueError(Throwable error) {
            responses.add(error);
        }

        @Override
        public void fetch(LDContext context, Callback<String> callback) {
            Object response = responses.poll();
            if (response == null) {
                callback.onError(new RuntimeException("MockFetcher: no responses queued"));
                return;
            }
            if (response instanceof Throwable) {
                callback.onError((Throwable) response);
            } else {
                callback.onSuccess((String) response);
            }
        }

        @Override
        public void close() {}
    }

    private FDv1PollingSynchronizer makeSynchronizer(MockFetcher fetcher) {
        return makeSynchronizer(fetcher, 0, 60_000);
    }

    private FDv1PollingSynchronizer makeSynchronizer(MockFetcher fetcher, long initialDelay, long pollInterval) {
        return new FDv1PollingSynchronizer(
                CONTEXT, fetcher, false, executor,
                initialDelay, pollInterval, LOGGER);
    }

    @Test
    public void successfulPollReturnsChangeSet() throws Exception {
        MockFetcher fetcher = new MockFetcher();
        fetcher.queueSuccess(VALID_FDV1_JSON);

        FDv1PollingSynchronizer sync = makeSynchronizer(fetcher);
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

    @Test
    public void nonRecoverableHttpErrorReturnsTerminalError() throws Exception {
        MockFetcher fetcher = new MockFetcher();
        fetcher.queueError(new LDInvalidResponseCodeFailure(
                "Unexpected response", 401, true));

        FDv1PollingSynchronizer sync = makeSynchronizer(fetcher);
        try {
            FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);

            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
        } finally {
            sync.close();
        }
    }

    @Test
    public void recoverableHttpErrorReturnsInterrupted() throws Exception {
        MockFetcher fetcher = new MockFetcher();
        fetcher.queueError(new LDInvalidResponseCodeFailure(
                "Unexpected response", 500, true));
        fetcher.queueSuccess(SINGLE_FLAG_JSON);

        FDv1PollingSynchronizer sync = makeSynchronizer(fetcher, 0, 100);
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

    @Test
    public void closeReturnsShutdown() throws Exception {
        MockFetcher fetcher = new MockFetcher();

        FDv1PollingSynchronizer sync = makeSynchronizer(fetcher, 60_000, 60_000);
        sync.close();

        FDv2SourceResult result = sync.next().get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());
    }

    @Test
    public void invalidJsonReturnsInterrupted() throws Exception {
        MockFetcher fetcher = new MockFetcher();
        fetcher.queueSuccess("not valid json");

        FDv1PollingSynchronizer sync = makeSynchronizer(fetcher);
        try {
            FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
        } finally {
            sync.close();
        }
    }

    @Test
    public void emptyResponseReturnsChangeSetWithNoFlags() throws Exception {
        MockFetcher fetcher = new MockFetcher();
        fetcher.queueSuccess("{}");

        FDv1PollingSynchronizer sync = makeSynchronizer(fetcher);
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

    @Test
    public void networkErrorReturnsInterrupted() throws Exception {
        MockFetcher fetcher = new MockFetcher();
        fetcher.queueError(new LDFailure("Exception while fetching flags",
                new IOException("connection refused"),
                LDFailure.FailureType.NETWORK_FAILURE));

        FDv1PollingSynchronizer sync = makeSynchronizer(fetcher);
        try {
            FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);

            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
        } finally {
            sync.close();
        }
    }

    @Test
    public void terminalErrorStopsPolling() throws Exception {
        MockFetcher fetcher = new MockFetcher();
        fetcher.queueError(new LDInvalidResponseCodeFailure(
                "Unexpected response", 401, true));
        fetcher.queueSuccess(SINGLE_FLAG_JSON);

        FDv1PollingSynchronizer sync = makeSynchronizer(fetcher, 0, 100);
        try {
            FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());

            Thread.sleep(500);

            assertEquals("second response should still be in the queue (never fetched)",
                    1, fetcher.responses.size());
        } finally {
            sync.close();
        }
    }

    @Test
    public void pollsRepeatAtConfiguredInterval() throws Exception {
        MockFetcher fetcher = new MockFetcher();
        fetcher.queueSuccess(SINGLE_FLAG_JSON);
        fetcher.queueSuccess(VALID_FDV1_JSON);

        FDv1PollingSynchronizer sync = makeSynchronizer(fetcher, 0, 200);
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
