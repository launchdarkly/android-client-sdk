package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.AssertHelpers.requireNoMoreValues;
import static com.launchdarkly.sdk.android.AssertHelpers.requireValue;

import static org.junit.Assert.assertEquals;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.DataSource;

import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class PollingDataSourceTest extends EasyMockSupport {
    private static final LDContext CONTEXT = LDContext.create("context-key");
    private static final LDConfig EMPTY_CONFIG = new LDConfig.Builder().build();

    private final MockComponents.MockDataSourceUpdateSink dataSourceUpdateSink = new MockComponents.MockDataSourceUpdateSink();
    private final MockFetcher fetcher = new MockFetcher();
    private final MockPlatformState platformState = new MockPlatformState();
    private final SimpleTestTaskExecutor taskExecutor = new SimpleTestTaskExecutor();
    private ClientContext baseClientContext;

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    @Before
    public void setup() {
        baseClientContext = ClientContextImpl.fromConfig(
                EMPTY_CONFIG, "", "", fetcher, CONTEXT,
                logging.logger, platformState, taskExecutor);
    }

    private ClientContext makeClientContext(boolean inBackground, Boolean previouslyInBackground) {
        ClientContext baseClientContext = ClientContextImpl.fromConfig(
                EMPTY_CONFIG, "", "", fetcher, CONTEXT,
                logging.logger, platformState, taskExecutor);
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
        ((ComponentsImpl.PollingDataSourceBuilderImpl)builder).pollIntervalMillisNoMinimum(200);
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
    public void firstPollHappensAfterBackgroundPollingIntervalWhenTransitioningToBackground() throws Exception {
        ClientContext clientContext = makeClientContext(true, false);
        PollingDataSourceBuilder builder = Components.pollingDataSource()
                .pollIntervalMillis(100000);
        ((ComponentsImpl.PollingDataSourceBuilderImpl)builder).backgroundPollIntervalMillisNoMinimum(200);
        DataSource ds = builder.build(clientContext);
        fetcher.setupSuccessResponse("{}");

        try {
            ds.start(LDUtil.noOpCallback());

            requireNoMoreValues(fetcher.receivedContexts, 10, TimeUnit.MILLISECONDS);

            Thread.sleep(300);

            LDContext context = requireValue(fetcher.receivedContexts, 100, TimeUnit.MILLISECONDS);
            assertEquals(CONTEXT, context);
        } finally {
            ds.stop(LDUtil.noOpCallback());
        }
    }

    @Test
    public void pollsAreRepeatedAtBackgroundPollIntervalInBackground() throws Exception {
        ClientContext clientContext = makeClientContext(true, null);
        PollingDataSourceBuilder builder = Components.pollingDataSource()
                .pollIntervalMillis(100000);
        ((ComponentsImpl.PollingDataSourceBuilderImpl)builder).backgroundPollIntervalMillisNoMinimum(200);
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
        ((ComponentsImpl.PollingDataSourceBuilderImpl)builder).pollIntervalMillisNoMinimum(200);
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
