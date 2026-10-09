package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.AssertHelpers.requireNoMoreValues;
import static com.launchdarkly.sdk.android.AssertHelpers.requireValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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
import java.util.List;
import java.util.Map;
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
        } finally {
            ds.stop(LDUtil.noOpCallback());
        }
    }

    private PollingDataSource makeDataSource(FeatureFetcher fetcherToUse, TaskExecutor executor,
                                             long pollIntervalMillis) {
        return new PollingDataSource(
                CONTEXT,
                dataSourceUpdateSink,
                0,
                pollIntervalMillis,
                Long.MAX_VALUE,
                fetcherToUse,
                executor,
                logging.logger
        );
    }

    @Test
    public void pollArmedBeforeStopDoesNotFetchWhenItFires() throws Exception {
        // Schedules tasks for real, but returns no handle, so stop has nothing to cancel.
        TaskExecutor executorWithoutCancellation = new TaskExecutor() {
            @Override
            public void executeOnMainThread(Runnable action) {
                taskExecutor.executeOnMainThread(action);
            }

            @Override
            public ScheduledFuture<?> scheduleTask(Runnable action, long delayMillis) {
                taskExecutor.scheduleTask(action, delayMillis);
                return null;
            }

            @Override
            public void close() {}
        };

        PollingDataSource ds = new PollingDataSource(
                CONTEXT, dataSourceUpdateSink, 100, 50, Long.MAX_VALUE,
                fetcher, executorWithoutCancellation, logging.logger);
        fetcher.setupSuccessResponse("{}");

        // Stop the data source while its first poll waits to run.
        ds.start(LDUtil.noOpCallback());
        ds.stop(LDUtil.noOpCallback());

        // A stopped data source sends no request, even from a poll it could not cancel.
        requireNoMoreValues(fetcher.receivedContexts, 500, TimeUnit.MILLISECONDS);
    }

    @Test
    public void fetchResultIsIgnoredWhenStopHappensWhileFetchIsInFlight() throws Exception {
        BlockingQueue<Callback<String>> heldFetches = new LinkedBlockingQueue<>();
        FeatureFetcher holdingFetcher = new FeatureFetcher() {
            @Override
            public void fetch(LDContext context, Callback<String> callback) {
                heldFetches.add(callback);
            }

            @Override
            public void close() {}
        };

        PollingDataSource ds = makeDataSource(holdingFetcher, taskExecutor, 50);
        List<String> results = new ArrayList<>();

        // Start a poll and wait until its request is outstanding.
        ds.start(new Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                results.add("success");
            }

            @Override
            public void onError(Throwable e) {
                results.add("error");
            }
        });
        Callback<String> heldFetch = requireValue(heldFetches, 500, TimeUnit.MILLISECONDS);

        // Stop the data source, then let the outstanding request complete.
        ds.stop(LDUtil.noOpCallback());
        heldFetch.onSuccess("{}");

        // A result that arrives after stop does not reach the consumer.
        assertEquals(0, results.size());
    }

    @Test
    public void pollingContinuesWhenResultCallbackThrowsOnSuccess() throws Exception {
        PollingDataSource ds = makeDataSource(fetcher, taskExecutor, 50);

        fetcher.setupSuccessResponse("{}");
        fetcher.setupSuccessResponse("{}");

        try {
            // Throw out of the success callback of every poll.
            ds.start(new Callback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    throw new RuntimeException("consumer failure");
                }

                @Override
                public void onError(Throwable e) {
                }
            });
            requireValue(fetcher.receivedContexts, 500, TimeUnit.MILLISECONDS);

            // A throwing consumer does not end the poll chain.
            requireValue(fetcher.receivedContexts, 500, TimeUnit.MILLISECONDS);
        } finally {
            ds.stop(LDUtil.noOpCallback());
        }
    }

    @Test
    public void pollingContinuesWhenResultCallbackThrowsOnError() throws Exception {
        PollingDataSource ds = makeDataSource(fetcher, taskExecutor, 50);

        fetcher.setupErrorResponse(new LDFailure("network error", LDFailure.FailureType.NETWORK_FAILURE));
        fetcher.setupSuccessResponse("{}");

        try {
            // Throw out of the error callback, which the first poll triggers by failing.
            ds.start(new Callback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                }

                @Override
                public void onError(Throwable e) {
                    throw new RuntimeException("consumer failure");
                }
            });
            requireValue(fetcher.receivedContexts, 500, TimeUnit.MILLISECONDS);

            // A failed poll whose consumer also throws still leads to the next poll.
            requireValue(fetcher.receivedContexts, 500, TimeUnit.MILLISECONDS);
        } finally {
            ds.stop(LDUtil.noOpCallback());
        }
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
