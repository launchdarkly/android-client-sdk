package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.internal.events.DefaultEventProcessor;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.EventSender;
import com.launchdarkly.sdk.internal.events.EventsConfiguration;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DiagnosticConfigTest {
    // The overall diagnostic event behavior is tested where it is implemented in java-sdk-internal.
    // These tests just verify that the Android SDK puts the appropriate configuration properties
    // into the diagnostic event, since those are specific to this SDK.

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();
    @Test
    public void defaultDiagnosticConfiguration() throws Exception {
        LDConfig ldConfig = new LDConfig.Builder().build();
        LDValue diagnosticJson = makeDiagnosticJson(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @Test
    public void customDiagnosticConfigurationGeneral() throws Exception {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
        LDConfig ldConfig = new LDConfig.Builder()
                .serviceEndpoints(Components.serviceEndpoints()
                        .events("https://1.1.1.1")
                        .polling("https://1.1.1.1")
                        .streaming("https://1.1.1.1"))
                .disableBackgroundUpdating(true)
                .evaluationReasons(true)
                .secondaryMobileKeys(secondaryKeys)
                .maxCachedContexts(-1)
                .build();

        LDValue diagnosticJson = makeDiagnosticJson(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        expected.put("backgroundPollingDisabled", true);
        expected.put("customBaseURI", true);
        expected.put("customEventsURI", true);
        expected.put("customStreamURI", true);
        expected.put("evaluationReasonsRequested", true);
        expected.put("mobileKeyCount", 2);
        expected.put("maxCachedUsers", -1);
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @Test
    public void customDiagnosticConfigurationEvents() throws Exception {
        LDConfig ldConfig = new LDConfig.Builder()
                .events(
                        Components.sendEvents()
                                .allAttributesPrivate(true)
                                .capacity(1000)
                                .diagnosticRecordingIntervalMillis(1_800_000)
                                .flushIntervalMillis(60_000)
                )
                .build();

        LDValue diagnosticJson = makeDiagnosticJson(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        expected.put("allAttributesPrivate", true);
        expected.put("diagnosticRecordingIntervalMillis", 1_800_000);
        expected.put("eventsCapacity", 1000);
        expected.put("eventsFlushIntervalMillis",60_000);
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @Test
    public void customDiagnosticConfigurationStreaming() throws Exception {
        LDConfig ldConfig = new LDConfig.Builder()
                .dataSource(
                        Components.streamingDataSource()
                                .backgroundPollIntervalMillis(900_000)
                                .initialReconnectDelayMillis(500)
                )
                .build();

        LDValue diagnosticJson = makeDiagnosticJson(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        expected.put("backgroundPollingIntervalMillis", 900_000);
        expected.put("reconnectTimeMillis", 500);
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @Test
    public void customDiagnosticConfigurationPolling() throws Exception {
        LDConfig ldConfig = new LDConfig.Builder()
                .dataSource(
                        Components.pollingDataSource()
                                .backgroundPollIntervalMillis(900_000)
                                .pollIntervalMillis(600_000)
                )
                .build();

        LDValue diagnosticJson = makeDiagnosticJson(ldConfig);
        ObjectBuilder expected = makeExpectedDefaultsWithoutStreaming();
        expected.put("streamingDisabled", true);
        expected.put("backgroundPollingIntervalMillis", 900_000);
        expected.put("pollingIntervalMillis", 600_000);
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @Test
    public void customDiagnosticConfigurationHttp() throws Exception {
        LDConfig ldConfig = new LDConfig.Builder()
                .http(
                        Components.httpConfiguration()
                                .connectTimeoutMillis(5_000)
                                .useReport(true)
                )
                .build();

        LDValue diagnosticJson = makeDiagnosticJson(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        expected.put("connectTimeoutMillis", 5_000);
        expected.put("useReport", true);
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    private static LDValue makeDiagnosticJson(LDConfig config) throws Exception {
        ClientContext clientContext = ClientContextImpl.fromConfig(config, "", "",
                null, null, LDLogger.none(), null, null);
        DiagnosticStore.SdkDiagnosticParams params = EventUtil.makeDiagnosticParams(clientContext);
        DiagnosticStore diagnosticStore = new DiagnosticStore(params);
        MockDiagnosticEventSender mockSender = new MockDiagnosticEventSender();
        EventsConfiguration eventsConfig = new EventsConfiguration(false, 100, null, 100000,
                diagnosticStore, mockSender, 1, null, 100000,
                false, false, null);
        try (DefaultEventProcessor eventProcessor = new DefaultEventProcessor(
                eventsConfig, EXECUTOR, Thread.MIN_PRIORITY, LDLogger.none())) {
            return mockSender.requireEvent();
        }
    }

    private static ObjectBuilder makeExpectedDefaultsWithoutStreaming() {
        ObjectBuilder expected = LDValue.buildObject();
        expected.put("allAttributesPrivate", false);
        expected.put("backgroundPollingDisabled", false);
        expected.put("backgroundPollingIntervalMillis",
                LDConfig.DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS);
        expected.put("connectTimeoutMillis",
                LDConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS);
        expected.put("customBaseURI", false);
        expected.put("customEventsURI", false);
        expected.put("customStreamURI", false);
        expected.put("diagnosticRecordingIntervalMillis",
                EventProcessorBuilder.DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS);
        expected.put("evaluationReasonsRequested", false);
        expected.put("eventsCapacity", EventProcessorBuilder.DEFAULT_CAPACITY);
        expected.put("eventsFlushIntervalMillis",
                EventProcessorBuilder.DEFAULT_FLUSH_INTERVAL_MILLIS);
        expected.put("maxCachedUsers", 5);
        expected.put("mobileKeyCount", 1);
        expected.put("streamingDisabled", false);
        expected.put("useReport", false);
        return expected;
    }

    private static ObjectBuilder makeExpectedDefaults() {
        return makeExpectedDefaultsWithoutStreaming()
                .put("reconnectTimeMillis", StreamingDataSourceBuilder.DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS);
    }

    private static class MockDiagnosticEventSender implements EventSender {
        private final BlockingQueue<LDValue> events = new LinkedBlockingQueue<>();

        @Override
        public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
            return null;
        }

        @Override
        public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
            events.add(LDValue.parse(new String(data, StandardCharsets.UTF_8)));
            return new Result(true, false, null);
        }

        @Override
        public void close() throws IOException {}

        public LDValue requireEvent() {
            try {
                LDValue value = events.poll(1, TimeUnit.SECONDS);
                if (value == null) {
                    throw new AssertionError("timed out waiting for diagnostic event");
                }
                return value.get("configuration");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
