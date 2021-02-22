package com.launchdarkly.android;

import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
        DiagnosticEvent.DiagnosticConfiguration diagnosticConfiguration = new DiagnosticEvent.DiagnosticConfiguration(ldConfig);
        JsonObject diagnosticJson = GsonCache.getGson().toJsonTree(diagnosticConfiguration).getAsJsonObject();
        JsonObject expected = new JsonObject();
        expected.addProperty("allAttributesPrivate", false);
        expected.addProperty("backgroundPollingDisabled", false);
        expected.addProperty("backgroundPollingIntervalMillis", 3_600_000);
        expected.addProperty("connectTimeoutMillis", 10_000);
        expected.addProperty("customBaseURI", false);
        expected.addProperty("customEventsURI", false);
        expected.addProperty("customStreamURI", false);
        expected.addProperty("diagnosticRecordingIntervalMillis", 900_000);
        expected.addProperty("evaluationReasonsRequested", false);
        expected.addProperty("eventsCapacity", 100);
        expected.addProperty("eventsFlushIntervalMillis",30_000);
        expected.addProperty("inlineUsersInEvents", false);
        expected.addProperty("mobileKeyCount", 1);
        expected.addProperty("pollingIntervalMillis", 300_000);
        expected.addProperty("streamingDisabled", false);
        expected.addProperty("useReport", false);
        expected.addProperty("maxCachedUsers", 5);
        expected.addProperty("autoAliasingOptOut", false);
        Assert.assertEquals(expected, diagnosticJson);
    }

    @Test
    public void testCustomDiagnosticConfiguration() {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
        LDConfig ldConfig = new LDConfig.Builder()
                .allAttributesPrivate()
                .disableBackgroundUpdating(true)
                .backgroundPollingIntervalMillis(900_000)
                .connectionTimeoutMillis(5_000)
                .pollUri(Uri.parse("https://1.1.1.1"))
                .eventsUri(Uri.parse("https://1.1.1.1"))
                .streamUri(Uri.parse("https://1.1.1.1"))
                .diagnosticRecordingIntervalMillis(1_800_000)
                .evaluationReasons(true)
                .eventsCapacity(1000)
                .eventsFlushIntervalMillis(60_000)
                .inlineUsersInEvents(true)
                .secondaryMobileKeys(secondaryKeys)
                .pollingIntervalMillis(600_000)
                .stream(false)
                .useReport(true)
                .maxCachedUsers(-1)
                .autoAliasingOptOut(true)
                .build();

        DiagnosticEvent.DiagnosticConfiguration diagnosticConfiguration = new DiagnosticEvent.DiagnosticConfiguration(ldConfig);
        JsonObject diagnosticJson = GsonCache.getGson().toJsonTree(diagnosticConfiguration).getAsJsonObject();
        JsonObject expected = new JsonObject();
        expected.addProperty("allAttributesPrivate", true);
        expected.addProperty("backgroundPollingDisabled", true);
        expected.addProperty("backgroundPollingIntervalMillis", 900_000);
        expected.addProperty("connectTimeoutMillis", 5_000);
        expected.addProperty("customBaseURI", true);
        expected.addProperty("customEventsURI", true);
        expected.addProperty("customStreamURI", true);
        expected.addProperty("diagnosticRecordingIntervalMillis", 1_800_000);
        expected.addProperty("evaluationReasonsRequested", true);
        expected.addProperty("eventsCapacity", 1000);
        expected.addProperty("eventsFlushIntervalMillis",60_000);
        expected.addProperty("inlineUsersInEvents", true);
        expected.addProperty("mobileKeyCount", 2);
        expected.addProperty("pollingIntervalMillis", 600_000);
        expected.addProperty("streamingDisabled", true);
        expected.addProperty("useReport", true);
        expected.addProperty("maxCachedUsers", -1);
        expected.addProperty("autoAliasingOptOut", true);
        Assert.assertEquals(expected, diagnosticJson);
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
}
