package com.launchdarkly.android;

import android.support.test.annotation.UiThreadTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.google.gson.JsonObject;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class LDClientTest {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    private LDClient ldClient;

    @Before
    public void before() {

    }

    @UiThreadTest // Not testing UI things, but we need to simulate the UI so the Foreground class is happy.
    @Test
    public void TestOfflineClientReturnsFallbacks() throws ExecutionException, InterruptedException {
        LDConfig config = new LDConfig.Builder()
                .setOffline(true)
                .build();

        LDUser user = new LDUser.Builder("userKey").build();
        ldClient = LDClient.init(activityTestRule.getActivity().getApplication(), config, user, 1);

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        assertTrue(ldClient.boolVariation("boolFlag", true));
        assertEquals(1.0F, ldClient.floatVariation("floatFlag", 1.0F));
        assertEquals(Integer.valueOf(1), ldClient.intVariation("intFlag", 1));
        assertEquals("fallback", ldClient.stringVariation("stringFlag", "fallback"));

        JsonObject expectedJson = new JsonObject();
        expectedJson.addProperty("field", "value");
        assertEquals(expectedJson, ldClient.jsonVariation("jsonFlag", expectedJson));
    }

    @UiThreadTest // Not testing UI things, but we need to simulate the UI so the Foreground class is happy.
    @Test
    public void GivenFallbacksAreNullAndTestOfflineClientReturnsFallbacks() throws ExecutionException, InterruptedException {
        LDConfig config = new LDConfig.Builder()
                .setOffline(true)
                .build();

        LDUser user = new LDUser.Builder("userKey").build();
        ldClient = LDClient.init(activityTestRule.getActivity().getApplication(), config, user, 1);

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());
        assertEquals(null, ldClient.jsonVariation("jsonFlag", null));

        assertEquals(null, ldClient.boolVariation("boolFlag", null));
        assertEquals(null, ldClient.floatVariation("floatFlag", null));
        assertEquals(null, ldClient.intVariation("intFlag", null));
        assertEquals(null, ldClient.stringVariation("stringFlag", null));
    }
}