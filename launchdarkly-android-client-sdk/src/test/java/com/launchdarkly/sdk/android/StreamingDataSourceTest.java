package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.DataSource;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

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

    private final IEnvironmentReporter environmentReporter = new EnvironmentReporterBuilder().build();
    private final SimpleTestTaskExecutor taskExecutor = new SimpleTestTaskExecutor();

    private ClientContext makeClientContext(boolean inBackground, Boolean previouslyInBackground) {
        ClientContext baseClientContext = ClientContextImpl.fromConfig(
                new LDConfig.Builder().build(), "", "", null, CONTEXT,
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
                new LDConfig.Builder().build(), "", "", makeFeatureFetcher(), CONTEXT,
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
}
