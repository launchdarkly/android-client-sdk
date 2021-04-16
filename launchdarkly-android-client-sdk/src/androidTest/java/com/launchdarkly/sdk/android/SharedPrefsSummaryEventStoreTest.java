package com.launchdarkly.sdk.android;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


@RunWith(AndroidJUnit4.class)
public class SharedPrefsSummaryEventStoreTest {
    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    private LDClient ldClient;
    private LDConfig ldConfig;
    private LDUser ldUser;
    private SummaryEventStore summaryEventStore;

    @Before
    public void setUp() {
        ldConfig = new LDConfig.Builder()
                .offline(true)
                .build();

        ldUser = new LDUser.Builder("userKey").build();

        ldClient = LDClient.init(ApplicationProvider.getApplicationContext(), ldConfig, ldUser, 1);
        summaryEventStore = ldClient.getSummaryEventStore();
    }

    @Test
    public void startDateIsSaved() {
        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        ldClient.boolVariation("boolFlag", true);
        ldClient.jsonValueVariation("jsonFlag", LDValue.ofNull());

        SummaryEvent summaryEvent = summaryEventStore.getSummaryEvent();
        assertNotNull(summaryEvent.startDate);
    }

    @Test
    public void counterIsUpdated() {
        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        ldClient.boolVariation("boolFlag", true);
        Map<String, SummaryEventStore.FlagCounters> features = summaryEventStore.getSummaryEvent().features;
        List<SummaryEventStore.FlagCounter> counters = features.get("boolFlag").counters;

        assertEquals(1, counters.size());
        assertEquals(1, counters.get(0).count);

        ldClient.boolVariation("boolFlag", true);
        features = summaryEventStore.getSummaryEvent().features;
        counters =  features.get("boolFlag").counters;

        assertEquals(1, counters.size());
        assertEquals(2, counters.get(0).count);
    }

    @Test
    public void evaluationsAreSaved() {
        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        ldClient.boolVariation("boolFlag", true);
        ldClient.intVariation("intFlag", 6);
        ldClient.doubleVariation("doubleFlag", 0.2);
        ldClient.stringVariation("stringFlag", "string");
        ldClient.jsonValueVariation("jsonFlag", LDValue.ofNull());

        Map<String, SummaryEventStore.FlagCounters> features = summaryEventStore.getSummaryEvent().features;
        assertEquals(5, features.size());

        assertEquals(LDValueType.BOOLEAN, features.get("boolFlag").defaultValue.getType());
        assertTrue(features.get("boolFlag").defaultValue.booleanValue());

        assertEquals(LDValueType.NUMBER, features.get("intFlag").defaultValue.getType());
        assertEquals(6, features.get("intFlag").defaultValue.intValue());

        assertEquals(LDValueType.NUMBER, features.get("doubleFlag").defaultValue.getType());
        assertEquals(0.2, features.get("doubleFlag").defaultValue.doubleValue(), 0.0);

        assertEquals(LDValueType.STRING, features.get("stringFlag").defaultValue.getType());
        assertEquals("string", features.get("stringFlag").defaultValue.stringValue());

        assertNull(features.get("jsonFlag").defaultValue);
    }

    @Test
    public void sharedPreferencesAreCleared() {
        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        ldClient.boolVariation("boolFlag", true);
        ldClient.stringVariation("stringFlag", "string");

        Map<String, SummaryEventStore.FlagCounters> features = summaryEventStore.getSummaryEvent().features;

        Assert.assertTrue(features.containsKey("boolFlag"));
        Assert.assertTrue(features.containsKey("stringFlag"));

        summaryEventStore.clear();

        SummaryEvent summaryEvent = summaryEventStore.getSummaryEvent();
        assertNull(summaryEvent);
    }
}