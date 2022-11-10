package com.launchdarkly.sdk.android;

import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;

@RunWith(AndroidJUnit4.class)
public class DiagnosticEventTest {

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Test
    public void defaultDiagnosticConfiguration() {
        LDConfig ldConfig = new LDConfig.Builder().build();
        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @Test
    public void customDiagnosticConfigurationGeneral() {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
        LDConfig ldConfig = new LDConfig.Builder()
                .disableBackgroundUpdating(true)
                .connectionTimeoutMillis(5_000)
                .pollUri(Uri.parse("https://1.1.1.1"))
                .eventsUri(Uri.parse("https://1.1.1.1"))
                .streamUri(Uri.parse("https://1.1.1.1"))
                .evaluationReasons(true)
                .secondaryMobileKeys(secondaryKeys)
                .useReport(true)
                .maxCachedUsers(-1)
                .autoAliasingOptOut(true)
                .build();

        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        expected.put("backgroundPollingDisabled", true);
        expected.put("connectTimeoutMillis", 5_000);
        expected.put("customBaseURI", true);
        expected.put("customEventsURI", true);
        expected.put("customStreamURI", true);
        expected.put("evaluationReasonsRequested", true);
        expected.put("mobileKeyCount", 2);
        expected.put("useReport", true);
        expected.put("maxCachedUsers", -1);
        expected.put("autoAliasingOptOut", true);

        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @Test
    public void customDiagnosticConfigurationEvents() {
        LDConfig ldConfig = new LDConfig.Builder()
                .events(
                        Components.sendEvents()
                                .allAttributesPrivate(true)
                                .capacity(1000)
                                .diagnosticRecordingIntervalMillis(1_800_000)
                                .flushIntervalMillis(60_000)
                                .inlineUsers(true)
                )
                .build();

        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        expected.put("allAttributesPrivate", true);
        expected.put("diagnosticRecordingIntervalMillis", 1_800_000);
        expected.put("eventsCapacity", 1000);
        expected.put("eventsFlushIntervalMillis", 60_000);
        expected.put("inlineUsersInEvents", true);
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @Test
    public void customDiagnosticConfigurationStreaming() {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
        LDConfig ldConfig = new LDConfig.Builder()
                .dataSource(
                        Components.streamingDataSource()
                                .backgroundPollIntervalMillis(900_000)
                                .initialReconnectDelayMillis(500)
                )
                .build();

        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        expected.put("backgroundPollingIntervalMillis", 900_000);
        expected.put("reconnectTimeMillis", 500);
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @Test
    public void customDiagnosticConfigurationPolling() {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
        LDConfig ldConfig = new LDConfig.Builder()
                .dataSource(
                        Components.pollingDataSource()
                                .backgroundPollIntervalMillis(900_000)
                                .pollIntervalMillis(600_000)
                )
                .build();

        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        ObjectBuilder expected = makeExpectedDefaultsWithoutStreaming();
        expected.put("streamingDisabled", true);
        expected.put("backgroundPollingIntervalMillis", 900_000);
        expected.put("pollingIntervalMillis", 600_000);
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void customDiagnosticConfigurationEventsWithDeprecatedSetters() {
        LDConfig ldConfig = new LDConfig.Builder()
                .allAttributesPrivate()
                .diagnosticRecordingIntervalMillis(1_800_000)
                .eventsCapacity(1000)
                .eventsFlushIntervalMillis(60_000)
                .inlineUsersInEvents(true)
                .build();

        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        expected.put("allAttributesPrivate", true);
        expected.put("diagnosticRecordingIntervalMillis", 1_800_000);
        expected.put("eventsCapacity", 1000);
        expected.put("eventsFlushIntervalMillis", 60_000);
        expected.put("inlineUsersInEvents", true);
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void customDiagnosticConfigurationStreamingWithDeprecatedSetters() {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
        LDConfig ldConfig = new LDConfig.Builder()
                .backgroundPollingIntervalMillis(900_000)
                .build();

        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        ObjectBuilder expected = makeExpectedDefaults();
        expected.put("backgroundPollingIntervalMillis", 900_000);
        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void customDiagnosticConfigurationPollingWithDeprecatedSetters() {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
        LDConfig ldConfig = new LDConfig.Builder()
                .stream(false)
                .backgroundPollingIntervalMillis(900_000)
                .pollingIntervalMillis(600_000)
                .build();

        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        ObjectBuilder expected = makeExpectedDefaultsWithoutStreaming();
        expected.put("streamingDisabled", true);
        expected.put("backgroundPollingIntervalMillis", 900_000);
        expected.put("pollingIntervalMillis", 600_000);

        // When using the deprecated setters only, there is an extra defaulting rule that causes
        // the event flush interval to match the polling interval if not otherwise specified.
        expected.put("eventsFlushIntervalMillis", 600_000);

        Assert.assertEquals(expected.build(), diagnosticJson);
    }

    @Test
    public void statisticsEventSerialization() {
        DiagnosticEvent.Statistics statisticsEvent = new DiagnosticEvent.Statistics(2_000,
                new DiagnosticId("testid", "testkey"), 1_000, 5, 100,
                Collections.singletonList(new DiagnosticEvent.StreamInit(100, 50, false)));
        JsonObject diagnosticJson = GsonCache.getGson().toJsonTree(statisticsEvent).getAsJsonObject();
        JsonObject expected = new JsonObject();
        expected.addProperty("kind", "diagnostic");
        expected.addProperty("creationDate", 2_000);
        JsonObject expectedId = new JsonObject();
        expectedId.addProperty("diagnosticId", "testid");
        expectedId.addProperty("sdkKeySuffix", "estkey");
        expected.add("id", expectedId);
        expected.addProperty("dataSinceDate", 1_000);
        expected.addProperty("droppedEvents", 5);
        expected.addProperty("eventsInLastBatch", 100);
        JsonArray expectedStreamInits = new JsonArray();
        JsonObject expectedStreamInit = new JsonObject();
        expectedStreamInit.addProperty("timestamp", 100);
        expectedStreamInit.addProperty("durationMillis", 50);
        expectedStreamInit.addProperty("failed", false);
        expectedStreamInits.add(expectedStreamInit);
        expected.add("streamInits", expectedStreamInits);
        Assert.assertEquals(expected, diagnosticJson);
    }

    private static ObjectBuilder makeExpectedDefaultsWithoutStreaming() {
        ObjectBuilder expected = LDValue.buildObject();
        expected.put("allAttributesPrivate", false);
        expected.put("autoAliasingOptOut", false);
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
        expected.put("inlineUsersInEvents", false);
        expected.put("maxCachedUsers", 5);
        expected.put("mobileKeyCount", 1);
        expected.put("streamingDisabled", false);
        expected.put("useReport", false);
        return expected;
    }

    private static ObjectBuilder makeExpectedDefaults() {
        return makeExpectedDefaultsWithoutStreaming()
                .put("reconnectTimeMillis",
                        StreamingDataSourceBuilder.DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS);
    }
}
