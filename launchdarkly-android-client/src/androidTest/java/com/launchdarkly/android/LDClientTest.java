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

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

@RunWith(AndroidJUnit4.class)
public class LDClientTest {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    private LDClient ldClient;
    private Future<LDClient> ldClientFuture;
    private LDConfig ldConfig;
    private LDUser ldUser;

    @Before
    public void setUp() {
        ldConfig = new LDConfig.Builder()
                .setOffline(true)
                .build();

        ldUser = new LDUser.Builder("userKey").build();
    }

    @UiThreadTest
    // Not testing UI things, but we need to simulate the UI so the Foreground class is happy.
    @Test
    public void testOfflineClientReturnsFallbacks() {
        ldClient = LDClient.init(activityTestRule.getActivity().getApplication(), ldConfig, ldUser, 1);
        ldClient.clearSummaryEventSharedPreferences();

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        assertTrue(ldClient.boolVariation("boolFlag", true));
        assertEquals(1.0F, ldClient.floatVariation("floatFlag", 1.0F));
        assertEquals(Integer.valueOf(1), ldClient.intVariation("intFlag", 1));
        assertEquals("fallback", ldClient.stringVariation("stringFlag", "fallback"));

        JsonObject expectedJson = new JsonObject();
        expectedJson.addProperty("field", "value");
        assertEquals(expectedJson, ldClient.jsonVariation("jsonFlag", expectedJson));

        ldClient.clearSummaryEventSharedPreferences();
    }

    @UiThreadTest
    // Not testing UI things, but we need to simulate the UI so the Foreground class is happy.
    @Test
    public void givenFallbacksAreNullAndTestOfflineClientReturnsFallbacks() {
        ldClient = LDClient.init(activityTestRule.getActivity().getApplication(), ldConfig, ldUser, 1);
        ldClient.clearSummaryEventSharedPreferences();

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());
        assertNull(ldClient.jsonVariation("jsonFlag", null));

        assertNull(ldClient.boolVariation("boolFlag", null));
        assertNull(ldClient.floatVariation("floatFlag", null));
        assertNull(ldClient.intVariation("intFlag", null));
        assertNull(ldClient.stringVariation("stringFlag", null));

        ldClient.clearSummaryEventSharedPreferences();
    }

    @UiThreadTest
    @Test
    public void testInitMissingApplication() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        //noinspection ConstantConditions
        ldClientFuture = LDClient.init(null, ldConfig, ldUser);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertThat(actualFutureException, instanceOf(ExecutionException.class));
        assertThat(actualProvidedException, instanceOf(LaunchDarklyException.class));
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    @UiThreadTest
    @Test
    public void testInitMissingConfig() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        //noinspection ConstantConditions
        ldClientFuture = LDClient.init(activityTestRule.getActivity().getApplication(), null, ldUser);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertThat(actualFutureException, instanceOf(ExecutionException.class));
        assertThat(actualProvidedException, instanceOf(LaunchDarklyException.class));
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    @UiThreadTest
    @Test
    public void testInitMissingUser() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        //noinspection ConstantConditions
        ldClientFuture = LDClient.init(activityTestRule.getActivity().getApplication(), ldConfig, null);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertThat(actualFutureException, instanceOf(ExecutionException.class));
        assertThat(actualProvidedException, instanceOf(LaunchDarklyException.class));
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    @UiThreadTest
    @Test
    public void testDoubleClose() throws IOException {
        ldClient = LDClient.init(activityTestRule.getActivity().getApplication(), ldConfig, ldUser, 1);
        ldClient.close();
        ldClient.close();
    }
}
