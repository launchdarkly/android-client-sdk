package com.launchdarkly.android;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonObject;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

@RunWith(AndroidJUnit4.class)
public class MultiEnvironmentLDClientTest {

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    private LDClient ldClient;
    private Future<LDClient> ldClientFuture;
    private LDConfig ldConfig;
    private LDUser ldUser;

    @Before
    public void setUp() {
        Map<String, String> secondaryKeys = new HashMap<>();
        secondaryKeys.put("test", "test");
        secondaryKeys.put("test1", "test1");

        ldConfig = new LDConfig.Builder()
                .setOffline(true)
                .setSecondaryMobileKeys(secondaryKeys)
                .build();

        ldUser = new LDUser.Builder("userKey").build();
    }

    @Test
    public void testOfflineClientReturnsFallbacks() {
        ldClient = LDClient.init(ApplicationProvider.getApplicationContext(), ldConfig, ldUser, 1);

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        assertTrue(ldClient.boolVariation("boolFlag", true));
        assertEquals(1.5, ldClient.doubleVariation("doubleFlag", 1.5));
        assertEquals(1, ldClient.intVariation("intFlag", 1));
        assertEquals("fallback", ldClient.stringVariation("stringFlag", "fallback"));

        JsonObject expectedJson = new JsonObject();
        expectedJson.addProperty("field", "value");
        assertEquals(expectedJson, ldClient.jsonVariation("jsonFlag", expectedJson));
    }

    @Test
    public void givenFallbacksAreNullAndTestOfflineClientReturnsFallbacks() {
        ldClient = LDClient.init(ApplicationProvider.getApplicationContext(), ldConfig, ldUser, 1);

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());
        assertNull(ldClient.jsonVariation("jsonFlag", null));
        assertNull(ldClient.stringVariation("stringFlag", null));
    }

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

    @Test
    public void testInitMissingConfig() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        //noinspection ConstantConditions
        ldClientFuture = LDClient.init(ApplicationProvider.getApplicationContext(), null, ldUser);

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

    @Test
    public void testInitMissingUser() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        //noinspection ConstantConditions
        ldClientFuture = LDClient.init(ApplicationProvider.getApplicationContext(), ldConfig, null);

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
}
