package com.launchdarkly.sdk.android;

import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.launchdarkly.sdk.LDValue;
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
    public void testDefaultDiagnosticConfiguration() {
        LDConfig ldConfig = new LDConfig.Builder().build();
        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        JsonObject expected = new JsonObject();
        setExpectedDefaults(expected);
        Assert.assertEquals(LDValue.parse(expected.toString()), diagnosticJson);
    }

    @Test
    public void testCustomDiagnosticConfigurationGeneral() {
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
        JsonObject expected = new JsonObject();
        setExpectedDefaults(expected);

        expected.addProperty("backgroundPollingDisabled", true);
        expected.addProperty("connectTimeoutMillis", 5_000);
        expected.addProperty("customBaseURI", true);
        expected.addProperty("customEventsURI", true);
        expected.addProperty("customStreamURI", true);
        expected.addProperty("evaluationReasonsRequested", true);
        expected.addProperty("mobileKeyCount", 2);
        expected.addProperty("useReport", true);
        expected.addProperty("maxCachedUsers", -1);
        expected.addProperty("autoAliasingOptOut", true);

        Assert.assertEquals(LDValue.parse(expected.toString()), diagnosticJson);
    }

    @Test
    public void testCustomDiagnosticConfigurationEvents() {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
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
        JsonObject expected = new JsonObject();
        setExpectedDefaults(expected);

        expected.addProperty("allAttributesPrivate", true);
        expected.addProperty("diagnosticRecordingIntervalMillis", 1_800_000);
        expected.addProperty("eventsCapacity", 1_000);
        expected.addProperty("eventsFlushIntervalMillis", 60_000);
        expected.addProperty("inlineUsersInEvents", true);

        Assert.assertEquals(LDValue.parse(expected.toString()), diagnosticJson);
    }

    @Test
    public void testCustomDiagnosticConfigurationStreaming() {
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
        JsonObject expected = new JsonObject();
        setExpectedDefaults(expected);

        expected.addProperty("backgroundPollingIntervalMillis", 900_000);
        expected.addProperty("reconnectTimeMillis", 500);

        Assert.assertEquals(LDValue.parse(expected.toString()), diagnosticJson);
    }

    @Test
    public void testCustomDiagnosticConfigurationPolling() {
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
        JsonObject expected = new JsonObject();
        setExpectedDefaults(expected);

        expected.addProperty("streamingDisabled", true);
        expected.addProperty("backgroundPollingIntervalMillis", 900_000);
        expected.addProperty("pollingIntervalMillis", 600_000);
        expected.remove("reconnectTimeMillis");

        Assert.assertEquals(LDValue.parse(expected.toString()), diagnosticJson);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testCustomDiagnosticConfigurationEventsWithDeprecatedSetters() {
        LDConfig ldConfig = new LDConfig.Builder()
                .allAttributesPrivate()
                .diagnosticRecordingIntervalMillis(1_800_000)
                .eventsCapacity(1000)
                .eventsFlushIntervalMillis(60_000)
                .inlineUsersInEvents(true)
                .build();

        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        JsonObject expected = new JsonObject();
        setExpectedDefaults(expected);

        expected.addProperty("allAttributesPrivate", true);
        expected.addProperty("diagnosticRecordingIntervalMillis", 1_800_000);
        expected.addProperty("eventsCapacity", 1000);
        expected.addProperty("eventsFlushIntervalMillis",60_000);
        expected.addProperty("inlineUsersInEvents", true);

        Assert.assertEquals(LDValue.parse(expected.toString()), diagnosticJson);
    }

    @Deprecated
    @Test
    public void testCustomDiagnosticConfigurationStreamingWithDeprecatedSetters() {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
        LDConfig ldConfig = new LDConfig.Builder()
                .backgroundPollingIntervalMillis(900_000)
                .build();

        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        JsonObject expected = new JsonObject();
        setExpectedDefaults(expected);

        expected.addProperty("backgroundPollingIntervalMillis", 900_000);

        Assert.assertEquals(LDValue.parse(expected.toString()), diagnosticJson);
    }

    @Deprecated
    @Test
    public void testCustomDiagnosticConfigurationPollingWithDeprecatedSetters() {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
        LDConfig ldConfig = new LDConfig.Builder()
                .stream(false)
                .backgroundPollingIntervalMillis(900_000)
                .pollingIntervalMillis(600_000)
                .build();

        LDValue diagnosticJson = DiagnosticEvent.makeConfigurationInfo(ldConfig);
        JsonObject expected = new JsonObject();
        setExpectedDefaults(expected);

        expected.addProperty("streamingDisabled", true);
        expected.addProperty("backgroundPollingIntervalMillis", 900_000);
        expected.addProperty("pollingIntervalMillis", 600_000);
        expected.remove("reconnectTimeMillis");

        // When using the deprecated setters only, there is an extra defaulting rule that causes
        // the event flush interval to match the polling interval if not otherwise specified.
        expected.addProperty("eventsFlushIntervalMillis", 600_000);

        Assert.assertEquals(LDValue.parse(expected.toString()), diagnosticJson);
    }

    @Test
    public void testStatisticsEventSerialization(){
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

    private static void setExpectedDefaults(JsonObject expected) {
        expected.addProperty("allAttributesPrivate", false);
        expected.addProperty("backgroundPollingDisabled", false);
        expected.addProperty("backgroundPollingIntervalMillis",
                LDConfig.DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS);
        expected.addProperty("connectTimeoutMillis",
                LDConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS);
        expected.addProperty("customBaseURI", false);
        expected.addProperty("customEventsURI", false);
        expected.addProperty("customStreamURI", false);
        expected.addProperty("diagnosticRecordingIntervalMillis",
                EventProcessorBuilder.DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS);
        expected.addProperty("evaluationReasonsRequested", false);
        expected.addProperty("eventsCapacity", EventProcessorBuilder.DEFAULT_CAPACITY);
        expected.addProperty("eventsFlushIntervalMillis",
                EventProcessorBuilder.DEFAULT_FLUSH_INTERVAL_MILLIS);
        expected.addProperty("inlineUsersInEvents", false);
        expected.addProperty("mobileKeyCount", 1);
        expected.addProperty("reconnectTimeMillis",
                StreamingDataSourceBuilder.DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS);
        expected.addProperty("streamingDisabled", false);
        expected.addProperty("useReport", false);
        expected.addProperty("maxCachedUsers", 5);
        expected.addProperty("autoAliasingOptOut", false);
    }
}
