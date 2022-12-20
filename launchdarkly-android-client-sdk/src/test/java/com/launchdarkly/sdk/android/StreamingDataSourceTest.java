package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.DataSource;

import org.junit.Rule;
import org.junit.Test;

public class StreamingDataSourceTest {
    // This class doesn't currently include any tests done with real HTTP, because we don't yet have
    // a good solution for setting up an embedded HTTP server with chunked streaming data. When we
    // get the java-test-helpers nanohttpd wrapper working, we can address that. In the meantime,
    // the tests here cover other aspects of how the streaming data source component behaves.

    private static final LDContext CONTEXT = LDContext.create("context-key");

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    private final MockComponents.MockDataSourceUpdateSink dataSourceUpdateSink = new MockComponents.MockDataSourceUpdateSink();
    private final MockPlatformState platformState = new MockPlatformState();
    private final SimpleTestTaskExecutor taskExecutor = new SimpleTestTaskExecutor();

    private ClientContext makeClientContext(boolean inBackground, Boolean previouslyInBackground) {
        ClientContext baseClientContext = ClientContextImpl.fromConfig(
                new LDConfig.Builder().build(), "", "", null, CONTEXT,
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
    public void pollingDataSourceHasInitialDelayWhenTransitioningToBackground() {
        ClientContext clientContext = makeClientContext(true, false);
        DataSource ds = Components.streamingDataSource()
                .backgroundPollIntervalMillis(999999)
                .build(clientContext);

        assertEquals(PollingDataSource.class, ds.getClass());

        assertEquals(999999L, ((PollingDataSource)ds).pollIntervalMillis);
        assertEquals(999999L, ((PollingDataSource)ds).initialDelayMillis);
    }

    @Test
    public void builderCreatesStreamingDataSourceWhenStartingInBackgroundWithOverride() {
        ClientContext clientContext = makeClientContext(true, null);
        DataSource ds = Components.streamingDataSource()
                .streamEvenInBackground(true)
                .build(clientContext);

        assertEquals(StreamingDataSource.class, ds.getClass());
    }
}
