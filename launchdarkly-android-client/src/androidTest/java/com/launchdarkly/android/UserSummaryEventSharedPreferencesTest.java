package com.launchdarkly.android;

import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.launchdarkly.android.test.TestActivity;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

/**
 * Created by jamesthacker on 4/16/18.
 */

@RunWith(AndroidJUnit4.class)
public class UserSummaryEventSharedPreferencesTest {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    private LDClient ldClient;
    private LDConfig ldConfig;
    private LDUser ldUser;
    private SummaryEventSharedPreferences summaryEventSharedPreferences;

    @Before
    public void setUp() {
        ldConfig = new LDConfig.Builder()
                .setOffline(true)
                .build();

        ldUser = new LDUser.Builder("userKey").build();

        ldClient = LDClient.init(activityTestRule.getActivity().getApplication(), ldConfig, ldUser, 1);
        ldClient.clearSummaryEventSharedPreferences();

        summaryEventSharedPreferences = ldClient.getSummaryEventSharedPreferences();
    }

    @After
    public void tearDown() {
        ldClient.clearSummaryEventSharedPreferences();
    }

    @Test
    public void startDateIsSaved() {
        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        ldClient.boolVariation("boolFlag", true);

        SummaryEvent summaryEvent = summaryEventSharedPreferences.getSummaryEvent();
        assertNotNull(summaryEvent.startDate);
    }

    @Test
    public void counterIsUpdated() {
        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());
        ldClient.clearSummaryEventSharedPreferences();

        ldClient.boolVariation("boolFlag", true);
        JsonObject features = summaryEventSharedPreferences.getSummaryEvent().features;
        JsonArray counters = features.get("boolFlag").getAsJsonObject().get("counters").getAsJsonArray();

        Assert.assertEquals(counters.size(), 1);

        JsonObject counter = counters.get(0).getAsJsonObject();

        Assert.assertEquals(counter.get("count").getAsInt(), 1);

        ldClient.boolVariation("boolFlag", true);
        features = summaryEventSharedPreferences.getSummaryEvent().features;
        counters = features.get("boolFlag").getAsJsonObject().get("counters").getAsJsonArray();

        Assert.assertEquals(counters.size(), 1);

        counter = counters.get(0).getAsJsonObject();

        Assert.assertEquals(counter.get("count").getAsInt(), 2);
    }

    @Test
    public void evaluationsAreSaved() {
        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        ldClient.boolVariation("boolFlag", true);
        ldClient.jsonVariation("jsonFlag", new JsonObject());
        ldClient.floatVariation("floatFlag", 0.1f);
        ldClient.intVariation("intFlag", 6);
        ldClient.stringVariation("stringFlag", "string");

        JsonObject features = summaryEventSharedPreferences.getSummaryEvent().features;

        Assert.assertTrue(features.keySet().contains("boolFlag"));
        Assert.assertTrue(features.keySet().contains("jsonFlag"));
        Assert.assertTrue(features.keySet().contains("floatFlag"));
        Assert.assertTrue(features.keySet().contains("intFlag"));
        Assert.assertTrue(features.keySet().contains("stringFlag"));

        Assert.assertEquals(true, features.get("boolFlag").getAsJsonObject().get("default").getAsBoolean());
        Assert.assertEquals(new JsonObject(), features.get("jsonFlag").getAsJsonObject().get("default").getAsJsonObject());
        Assert.assertEquals(0.1f, features.get("floatFlag").getAsJsonObject().get("default").getAsFloat());
        Assert.assertEquals(6, features.get("intFlag").getAsJsonObject().get("default").getAsInt());
        Assert.assertEquals("string", features.get("stringFlag").getAsJsonObject().get("default").getAsString());
    }

    @Test
    public void sharedPreferencesAreCleared() {
        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        ldClient.boolVariation("boolFlag", true);
        ldClient.stringVariation("stringFlag", "string");

        JsonObject features = summaryEventSharedPreferences.getSummaryEvent().features;

        Assert.assertTrue(features.keySet().contains("boolFlag"));
        Assert.assertTrue(features.keySet().contains("stringFlag"));

        ldClient.clearSummaryEventSharedPreferences();

        SummaryEvent summaryEvent = summaryEventSharedPreferences.getSummaryEvent();
        assertNull(summaryEvent);
    }
}
