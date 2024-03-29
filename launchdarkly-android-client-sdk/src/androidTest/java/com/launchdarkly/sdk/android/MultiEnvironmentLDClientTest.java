package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@RunWith(AndroidJUnit4.class)
public class MultiEnvironmentLDClientTest {

    @Rule
    public AndroidLoggingRule logging = new AndroidLoggingRule();

    private LDClient ldClient;
    private Future<LDClient> ldClientFuture;
    private LDConfig ldConfig;
    private LDContext ldContext;

    @Before
    public void setUp() {
        Map<String, String> secondaryKeys = new HashMap<>();
        secondaryKeys.put("test", "test");
        secondaryKeys.put("test1", "test1");

        ldConfig = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey("default-mobile-key")
                .offline(true)
                .secondaryMobileKeys(secondaryKeys)
                .logAdapter(logging.logAdapter)
                .loggerName(logging.loggerName)
                .build();

        ldContext = LDContext.create("userKey");
    }

    @Test
    public void testOfflineClientReturnsDefaults() {
        ldClient = LDClient.init(ApplicationProvider.getApplicationContext(), ldConfig, ldContext, 1);

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        assertTrue(ldClient.boolVariation("boolFlag", true));
        assertEquals(1.5, ldClient.doubleVariation("floatFlag", 1.5), 0.0);
        assertEquals(1, ldClient.intVariation("intFlag", 1));
        assertEquals("default", ldClient.stringVariation("stringFlag", "default"));

        LDValue expectedJson = LDValue.of("value");
        assertEquals(expectedJson, ldClient.jsonValueVariation("jsonFlag", expectedJson));
    }

    @Test
    public void givenDefaultsAreNullAndTestOfflineClientReturnsDefaults() {
        ldClient = LDClient.init(ApplicationProvider.getApplicationContext(), ldConfig, ldContext, 1);

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());
        assertNull(ldClient.stringVariation("stringFlag", null));
        assertEquals(ldClient.jsonValueVariation("jsonFlag", null), LDValue.ofNull());
    }

    @Test
    public void testInitMissingApplication() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        ldClientFuture = LDClient.init(null, ldConfig, ldContext);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertEquals(ExecutionException.class, actualFutureException.getClass());
        assertEquals(LaunchDarklyException.class, actualProvidedException.getClass());
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    @Test
    public void testInitMissingConfig() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        ldClientFuture = LDClient.init(ApplicationProvider.getApplicationContext(), null, ldContext);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertEquals(ExecutionException.class, actualFutureException.getClass());
        assertEquals(LaunchDarklyException.class, actualProvidedException.getClass());
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    @Test
    public void testInitMissingContext() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        ldClientFuture = LDClient.init(ApplicationProvider.getApplicationContext(), ldConfig,
                (LDContext)null);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertEquals(ExecutionException.class, actualFutureException.getClass());
        assertEquals(LaunchDarklyException.class, actualProvidedException.getClass());
        assertTrue("No future task to run", ldClientFuture.isDone());
    }
}
