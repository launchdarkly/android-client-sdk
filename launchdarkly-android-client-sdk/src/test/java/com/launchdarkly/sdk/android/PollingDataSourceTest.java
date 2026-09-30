package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.AssertHelpers.requireNoMoreValues;
import static com.launchdarkly.sdk.android.AssertHelpers.requireValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.DataSource;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PollingDataSourceTest {
    private static final LDContext CONTEXT = LDContext.create("context-key");
    private static final String MOBILE_KEY = "test-mobile-key";
    private static final LDConfig EMPTY_CONFIG = new LDConfig.Builder(AutoEnvAttributes.Disabled).build();

    private final MockComponents.MockDataSourceUpdateSink dataSourceUpdateSink = new MockComponents.MockDataSourceUpdateSink();
    private final MockFetcher fetcher = new MockFetcher();
    private final MockPlatformState platformState = new MockPlatformState();

    private final IEnvironmentReporter environmentReporter = new EnvironmentReporterBuilder().build();
    private final SimpleTestTaskExecutor taskExecutor = new SimpleTestTaskExecutor();
    private PersistentDataStoreWrapper.PerEnvironmentData perEnvironmentData;

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    @Before
    public void before() {
        perEnvironmentData = TestUtil.makeSimplePersistentDataStoreWrapper().perEnvironmentData(MOBILE_KEY);
    }

    private ClientContextImpl makeClientContext(boolean inBackground, Boolean previouslyInBackground) {
        ClientContextImpl baseClientContext = ClientContextImpl.fromConfig(
                EMPTY_CONFIG, "", "", perEnvironmentData, fetcher, CONTEXT,
                logging.logger, platformState, environmentReporter, taskExecutor);
        return ClientContextImpl.forDataSource(
                baseClientContext,
                dataSourceUpdateSink,
                CONTEXT,
                inBackground,
                previouslyInBackground
        );
    }

    @Test
    public void firstPollIsImmediateWhenStartingInForeground() throws Exception {
        ClientContext clientContext = makeClientContext(false, null);
        PollingDataSourceBuilder builder = Components.pollingDataSource()
                .pollIntervalMillis(100000)
                .backgroundPollIntervalMillis(100000);
        DataSource ds = builder.build(clientContext);
        fetcher.setupSuccessResponse("{}");

        try {
            ds.start(LDUtil.noOpCallback());
            LDContext context = requireValue(fetcher.receivedContexts, 500, TimeUnit.MILLISECONDS);
            assertEquals(CONTEXT, context);
        } finally {
            ds.stop(LDUtil.noOpCallback());
        }
    }

    @Test
    public void pollsAreRepeatedAtRegularPollIntervalInForeground() throws Exception {
        ClientContext clientContext = makeClientContext(false, null);
        PollingDataSourceBuilder builder = Components.pollingDataSource()
                .backgroundPollIntervalMillis(100000);
        ((ComponentsImpl.PollingDataSourceBuilderImpl) builder).pollIntervalMillisNoMinimum(200);
        DataSource ds = builder.build(clientContext);

        fetcher.setupSuccessResponse("{}");
        fetcher.setupSuccessResponse("{}");

        try {
            ds.start(LDUtil.noOpCallback());

            LDContext context1 = requireValue(fetcher.receivedContexts, 200, TimeUnit.MILLISECONDS);
            assertEquals(CONTEXT, context1);

            requireNoMoreValues(fetcher.receivedContexts, 10, TimeUnit.MILLISECONDS);

            Thread.sleep(2000);

            LDContext context2 = requireValue(fetcher.receivedContexts, 1, TimeUnit.MILLISECONDS);
            assertEquals(CONTEXT, context2);
        } finally {
            ds.stop(LDUtil.noOpCallback());
        }
    }

    @Test
    public void firstPollIsImmediateWhenStartingInBackground() throws Exception {
        ClientContext clientContext = makeClientContext(true, null);
        PollingDataSourceBuilder builder = Components.pollingDataSource()
                .pollIntervalMillis(100000)
                .backgroundPollIntervalMillis(100000);
        DataSource ds = builder.build(clientContext);
        fetcher.setupSuccessResponse("{}");

        try {
            ds.start(LDUtil.noOpCallback());
            LDContext context = requireValue(fetcher.receivedContexts, 500, TimeUnit.MILLISECONDS);
            assertEquals(CONTEXT, context);
        } finally {
            ds.stop(LDUtil.noOpCallback());
        }
    }

    @Test
    public void pollingIntervalHonoredAcrossMultipleBuildCalls() throws Exception {
        ClientContextImpl clientContext = makeClientContext(true, null);
        PollingDataSourceBuilder builder = Components.pollingDataSource()
                .pollIntervalMillis(100000)
                .backgroundPollIntervalMillis(100000);

        // first build should have no delay
        PollingDataSource ds1 = (PollingDataSource) builder.build(clientContext);
        assertEquals(0, ds1.initialDelayMillis);

        // simulate successful update of context index timestamp
        String hashedContextId = LDUtil.urlSafeBase64HashedContextId(CONTEXT);
        String fingerPrint = LDUtil.urlSafeBase64Hash(CONTEXT);
        PersistentDataStoreWrapper.PerEnvironmentData perEnvironmentData = clientContext.getPerEnvironmentData();
        perEnvironmentData.setContextData(hashedContextId, fingerPrint, new EnvironmentData());
        ContextIndex newIndex = perEnvironmentData.getIndex().updateTimestamp(hashedContextId, System.currentTimeMillis());
        perEnvironmentData.setIndex(newIndex);

        // second build should have a non-zero delay due to simulated response storing a recent timestamp
        PollingDataSource ds2 = (PollingDataSource) builder.build(clientContext);
        assertNotEquals(0, ds2.initialDelayMillis);
    }

    @Test
    public void oneShotPollingSetsMaxNumberOfPollsTo1() throws Exception {
        ClientContextImpl clientContext = makeClientContext(true, null);
        PollingDataSourceBuilder builder = Components.pollingDataSource().oneShot();

        PollingDataSource ds = (PollingDataSource) builder.build(clientContext);
        assertEquals(1, ds.numberOfPollsRemaining);
    }

    @Test
    public void oneShotIsPreventByRateLimiting() throws Exception {
        ClientContextImpl clientContext = makeClientContext(true, null);
        PollingDataSourceBuilder builder = Components.pollingDataSource()
                .pollIntervalMillis(100000).oneShot();

        // first build should have no delay
        PollingDataSource ds1 = (PollingDataSource) builder.build(clientContext);
        assertEquals(1, ds1.numberOfPollsRemaining);
        assertEquals(0, ds1.initialDelayMillis);

        // simulate successful update of context index timestamp
        String hashedContextId = LDUtil.urlSafeBase64HashedContextId(CONTEXT);
        String fingerPrint = LDUtil.urlSafeBase64Hash(CONTEXT);
        PersistentDataStoreWrapper.PerEnvironmentData perEnvironmentData = clientContext.getPerEnvironmentData();
        perEnvironmentData.setContextData(hashedContextId, fingerPrint, new EnvironmentData());
        ContextIndex newIndex = perEnvironmentData.getIndex().updateTimestamp(hashedContextId, System.currentTimeMillis());
        perEnvironmentData.setIndex(newIndex);

        // second build should have a non-zero delay and so one shot is prevented by max number of polls being 0.
        PollingDataSource ds2 = (PollingDataSource) builder.build(clientContext);
        assertEquals(0, ds2.numberOfPollsRemaining);
        assertNotEquals(0, ds2.initialDelayMillis);
    }

    @Test
    public void pollsAreRepeatedAtBackgroundPollIntervalInBackground() throws Exception {
        ClientContext clientContext = makeClientContext(true, null);
        PollingDataSourceBuilder builder = Components.pollingDataSource()
                .pollIntervalMillis(100000);
        ((ComponentsImpl.PollingDataSourceBuilderImpl) builder).backgroundPollIntervalMillisNoMinimum(200);
        DataSource ds = builder.build(clientContext);

        fetcher.setupSuccessResponse("{}");
        fetcher.setupSuccessResponse("{}");

        try {
            ds.start(LDUtil.noOpCallback());

            LDContext context1 = requireValue(fetcher.receivedContexts, 200, TimeUnit.MILLISECONDS);
            assertEquals(CONTEXT, context1);

            requireNoMoreValues(fetcher.receivedContexts, 10, TimeUnit.MILLISECONDS);

            Thread.sleep(2000);

            LDContext context2 = requireValue(fetcher.receivedContexts, 1, TimeUnit.MILLISECONDS);
            assertEquals(CONTEXT, context2);
        } finally {
            ds.stop(LDUtil.noOpCallback());
        }
    }

    @Test
    public void dataIsUpdatedAfterEachPoll() throws Exception {
        ClientContext clientContext = makeClientContext(false, null);
        PollingDataSourceBuilder builder = Components.pollingDataSource()
                .backgroundPollIntervalMillis(100000);
        ((ComponentsImpl.PollingDataSourceBuilderImpl) builder).pollIntervalMillisNoMinimum(200);
        DataSource ds = builder.build(clientContext);

        EnvironmentData data1 = new DataSetBuilder()
                .add("flag1", 1, LDValue.of("a"), 0)
                .build();
        EnvironmentData data2 = new DataSetBuilder()
                .add("flag1", 2, LDValue.of("b"), 1)
                .build();

        fetcher.setupSuccessResponse(data1.toJson());
        fetcher.setupSuccessResponse(data2.toJson());

        try {
            ds.start(LDUtil.noOpCallback());

            LDContext context1 = requireValue(fetcher.receivedContexts, 200, TimeUnit.MILLISECONDS);
            assertEquals(CONTEXT, context1);

            Map<String, DataModel.Flag> receivedData1 = dataSourceUpdateSink.expectInit();
            assertEquals(data1.getAll(), receivedData1);
            requireNoMoreValues(dataSourceUpdateSink.inits, 10, TimeUnit.MILLISECONDS);

            LDContext context2 = requireValue(fetcher.receivedContexts, 500, TimeUnit.MILLISECONDS);
            assertEquals(CONTEXT, context2);

            Map<String, DataModel.Flag> receivedData2 = dataSourceUpdateSink.expectInit();
            assertEquals(data2.getAll(), receivedData2);
        } finally {
            ds.stop(LDUtil.noOpCallback());
        }
    }

    @Test
    public void terminatesAfterMaxNumberOfPolls() throws Exception {
        ClientContextImpl clientContext = makeClientContext(false, null);
        PollingDataSource ds = new PollingDataSource(
                clientContext.getEvaluationContext(),
                clientContext.getDataSourceUpdateSink(),
                0,
                50,
                2, // maximum number of requests is 2
                clientContext.getFetcher(),
                clientContext.getPlatformState(),
                clientContext.getTaskExecutor(),
                clientContext.getBaseLogger()
        );

        fetcher.setupSuccessResponse("{}");
        fetcher.setupSuccessResponse("{}");
        fetcher.setupSuccessResponse("{}"); // need a third response to detect if the third request is sent which is a failure

        try {
            ds.start(LDUtil.noOpCallback());

            LDContext context1 = requireValue(fetcher.receivedContexts, 500, TimeUnit.MILLISECONDS);

            LDContext context2 = requireValue(fetcher.receivedContexts, 500, TimeUnit.MILLISECONDS);

            // if a third request is sent, this will fail here
            requireNoMoreValues(fetcher.receivedContexts, 200, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> pollTask = ds.currentPollTask.get();
            assertTrue("no further poll should be pending", pollTask == null || pollTask.isDone());
        } finally {
            ds.stop(LDUtil.noOpCallback());
        }
    }

    // --- backoff after failures ---
    //
    // These tests drive the data source's timers with a ManualTaskExecutor and a
    // PollingRetryState without jitter. The mock fetcher answers synchronously, so every poll and
    // its outcome happen inside runPendingTasks() and the scheduled delays can be asserted exactly.

    private static final long POLL_INTERVAL_MILLIS = 30_000;
    private static final long EXTENDED_DELAY_MILLIS = 300_000;

    private final ManualTaskExecutor manualTaskExecutor = new ManualTaskExecutor();

    private static LDInvalidResponseCodeFailure httpFailure(int status) {
        return new LDInvalidResponseCodeFailure("test failure", status, LDUtil.isHttpErrorRecoverable(status));
    }

    private static PollingRetryState retryStateWithoutJitter() {
        return new PollingRetryState(POLL_INTERVAL_MILLIS,
                new RetryRegime(EXTENDED_DELAY_MILLIS, EXTENDED_DELAY_MILLIS * 4),
                new Random() {
                    @Override
                    public double nextDouble() {
                        return 0;
                    }
                });
    }

    private PollingDataSource makePollingDataSource(long maxNumberOfPolls) {
        ClientContextImpl clientContext = makeClientContext(false, null);
        return new PollingDataSource(
                clientContext.getEvaluationContext(),
                clientContext.getDataSourceUpdateSink(),
                0,
                POLL_INTERVAL_MILLIS,
                maxNumberOfPolls,
                clientContext.getFetcher(),
                clientContext.getPlatformState(),
                manualTaskExecutor,
                retryStateWithoutJitter(),
                clientContext.getBaseLogger()
        );
    }

    private static class TrackingCallback implements Callback<Boolean> {
        final List<Boolean> successes = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();

        @Override
        public void onSuccess(Boolean result) {
            successes.add(result != null ? result : false);
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }
    }

    @Test
    public void normalErrorIsPolledAgainAfterPollInterval() {
        PollingDataSource ds = makePollingDataSource(Long.MAX_VALUE);
        fetcher.setupErrorResponse(httpFailure(500));
        fetcher.setupSuccessResponse("{}");
        TrackingCallback callback = new TrackingCallback();

        // The first poll fails with a 500.
        ds.start(callback);
        manualTaskExecutor.runPendingTasks();
        assertEquals(1, callback.errors.size());

        // The next poll is scheduled for the regular interval and succeeds.
        assertEquals(Collections.singletonList(POLL_INTERVAL_MILLIS), manualTaskExecutor.pendingDelaysMillis());
        manualTaskExecutor.runPendingTasks();
        assertEquals(1, callback.successes.size());
        assertEquals(2, fetcher.receivedContexts.size());
    }

    @Test
    public void unexpectedErrorIsPolledAgainAfterExtendedDelay() {
        PollingDataSource ds = makePollingDataSource(Long.MAX_VALUE);
        fetcher.setupErrorResponse(httpFailure(401));
        fetcher.setupSuccessResponse("{}");
        TrackingCallback callback = new TrackingCallback();

        // The first poll fails with a 401, which is reported without shutting the SDK down.
        ds.start(callback);
        manualTaskExecutor.runPendingTasks();
        assertEquals(401, ((LDInvalidResponseCodeFailure) callback.errors.get(0)).getResponseCode());
        assertFalse(dataSourceUpdateSink.shutDownCalled);

        // The next poll is scheduled for the extended delay rather than the poll interval, and
        // succeeds once that delay has passed.
        assertEquals(Collections.singletonList(EXTENDED_DELAY_MILLIS), manualTaskExecutor.pendingDelaysMillis());
        manualTaskExecutor.runPendingTasks();
        assertEquals(1, callback.successes.size());
        assertEquals(2, fetcher.receivedContexts.size());
    }

    @Test
    public void sustainedUnexpectedErrorsKeepPollingWithGrowingDelay() {
        PollingDataSource ds = makePollingDataSource(Long.MAX_VALUE);
        fetcher.setupErrorResponse(httpFailure(401));
        fetcher.setupErrorResponse(httpFailure(403));
        fetcher.setupErrorResponse(httpFailure(405));
        fetcher.setupSuccessResponse("{}");
        TrackingCallback callback = new TrackingCallback();

        // Each failure schedules the next poll with double the delay, and the data source never
        // gives up.
        ds.start(callback);
        manualTaskExecutor.runPendingTasks();
        for (long expectedDelay : new long[] {EXTENDED_DELAY_MILLIS, EXTENDED_DELAY_MILLIS * 2, EXTENDED_DELAY_MILLIS * 4}) {
            assertEquals(Collections.singletonList(expectedDelay), manualTaskExecutor.pendingDelaysMillis());
            manualTaskExecutor.runPendingTasks();
        }

        // The fourth poll succeeded.
        assertEquals(3, callback.errors.size());
        assertEquals(1, callback.successes.size());
    }

    @Test
    public void twoConsecutiveSuccessfulPollsResetBackoff() {
        PollingDataSource ds = makePollingDataSource(Long.MAX_VALUE);
        fetcher.setupErrorResponse(httpFailure(401));
        fetcher.setupSuccessResponse("{}");
        fetcher.setupSuccessResponse("{}");
        fetcher.setupErrorResponse(httpFailure(500));
        fetcher.setupSuccessResponse("{}");
        TrackingCallback callback = new TrackingCallback();

        // The 401 moves the data source to the extended delay.
        ds.start(callback);
        manualTaskExecutor.runPendingTasks();
        assertEquals(Collections.singletonList(EXTENDED_DELAY_MILLIS), manualTaskExecutor.pendingDelaysMillis());

        // One success returns to the regular interval. A second one clears the backoff, so the
        // 500 that follows is retried at the regular interval instead of a doubled extended delay.
        manualTaskExecutor.runPendingTasks();
        assertEquals(Collections.singletonList(POLL_INTERVAL_MILLIS), manualTaskExecutor.pendingDelaysMillis());
        manualTaskExecutor.runPendingTasks();
        assertEquals(Collections.singletonList(POLL_INTERVAL_MILLIS), manualTaskExecutor.pendingDelaysMillis());
        manualTaskExecutor.runPendingTasks();
        assertEquals(2, callback.errors.size());
        assertEquals(Collections.singletonList(POLL_INTERVAL_MILLIS), manualTaskExecutor.pendingDelaysMillis());
    }

    @Test
    public void oneShotPollIsNotRetriedAfterFailure() {
        PollingDataSource ds = makePollingDataSource(1);
        fetcher.setupErrorResponse(httpFailure(500));
        fetcher.setupSuccessResponse("{}");
        TrackingCallback callback = new TrackingCallback();

        // The single poll fails.
        ds.start(callback);
        manualTaskExecutor.runPendingTasks();
        assertEquals(1, callback.errors.size());

        // A one-shot data source is done after its one poll, so nothing further is scheduled.
        assertTrue(manualTaskExecutor.pendingDelaysMillis().isEmpty());
    }

    @Test
    public void stopCancelsPendingPoll() {
        PollingDataSource ds = makePollingDataSource(Long.MAX_VALUE);
        fetcher.setupErrorResponse(httpFailure(401));
        fetcher.setupSuccessResponse("{}");
        TrackingCallback callback = new TrackingCallback();

        // The 401 schedules a poll for the extended delay.
        ds.start(callback);
        manualTaskExecutor.runPendingTasks();
        assertEquals(Collections.singletonList(EXTENDED_DELAY_MILLIS), manualTaskExecutor.pendingDelaysMillis());

        // Stopping cancels it.
        ds.stop(LDUtil.noOpCallback());
        assertTrue(manualTaskExecutor.pendingDelaysMillis().isEmpty());
    }

    private class MockFetcher implements FeatureFetcher {
        BlockingQueue<LDContext> receivedContexts = new LinkedBlockingQueue<>();
        BlockingQueue<MockResponse> responses = new LinkedBlockingQueue<>();

        class MockResponse {
            final String data;
            final Throwable error;

            MockResponse(String data, Throwable error) {
                this.data = data;
                this.error = error;
            }
        }

        public void setupSuccessResponse(String data) {
            responses.add(new MockResponse(data, null));
        }

        public void setupErrorResponse(Throwable error) {
            responses.add(new MockResponse(null, error));
        }

        @Override
        public void fetch(LDContext context, Callback<String> callback) {
            logging.logger.debug("MockFeatureFetcher.fetch was called");
            receivedContexts.add(context);
            MockResponse response = responses.poll();
            if (response == null) {
                logging.logger.error("test error: FeatureFetcher got an unexpected call");
                throw new RuntimeException("FeatureFetcher got an unexpected call");
            }
            if (response.error == null) {
                callback.onSuccess(response.data);
            } else {
                callback.onError(response.error);
            }
        }

        @Override
        public void close() throws IOException {}
    }
}
