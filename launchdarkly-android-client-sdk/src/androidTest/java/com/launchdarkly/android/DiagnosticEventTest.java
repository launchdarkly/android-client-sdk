package com.launchdarkly.android;

import android.net.Uri;
import android.support.test.runner.AndroidJUnit4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import junit.framework.Assert;

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
        Assert.assertEquals(expected, diagnosticJson);
    }

    @Test
    public void testCustomDiagnosticConfiguration() {
        HashMap<String, String> secondaryKeys = new HashMap<>(1);
        secondaryKeys.put("secondary", "key");
        LDConfig ldConfig = new LDConfig.Builder()
                .allAttributesPrivate()
                .setDisableBackgroundUpdating(true)
                .setBackgroundPollingIntervalMillis(900_000)
                .setConnectionTimeoutMillis(5_000)
                .setPollUri(Uri.parse("https://1.1.1.1"))
                .setEventsUri(Uri.parse("https://1.1.1.1"))
                .setStreamUri(Uri.parse("https://1.1.1.1"))
                .setDiagnosticRecordingIntervalMillis(1_800_000)
                .setEvaluationReasons(true)
                .setEventsCapacity(1000)
                .setEventsFlushIntervalMillis(60_000)
                .setInlineUsersInEvents(true)
                .setSecondaryMobileKeys(secondaryKeys)
                .setPollingIntervalMillis(600_000)
                .setStream(false)
                .setUseReport(true)
                .setMaxCachedUsers(-1)
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
