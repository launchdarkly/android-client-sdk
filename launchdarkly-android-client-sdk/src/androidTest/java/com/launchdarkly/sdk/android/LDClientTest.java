package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@RunWith(AndroidJUnit4.class)
public class LDClientTest {
    private static final String mobileKey = "test-mobile-key";
    private static final LDContext ldContext = LDContext.create("userKey");

    private Application application;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void testOfflineClientReturnsDefaultsIfThereAreNoStoredFlags() throws Exception {
        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(), ldContext, 1)) {
            assertTrue("client was not initialized", ldClient.isInitialized());
            assertTrue("client was offline", ldClient.isOffline());

            assertTrue(ldClient.boolVariation("boolFlag", true));
            assertEquals(1.5, ldClient.doubleVariation("doubleFlag", 1.5), 0.0);
            assertEquals(1, ldClient.intVariation("intFlag", 1));
            assertEquals("default", ldClient.stringVariation("stringFlag", "default"));

            LDValue expectedJson = LDValue.of("value");
            assertEquals(expectedJson, ldClient.jsonValueVariation("jsonFlag", expectedJson));
        }
    }

    @Test
    public void testOfflineClientUsesStoredFlags() throws Exception {
        PersistentDataStore store = new InMemoryPersistentDataStore();
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(mobileKey)
                .offline(true)
                .persistentDataStore(store)
                .build();

        String flagKey = "flag-key", flagValue = "stored-value";
        Flag flag = new FlagBuilder(flagKey).version(1).value(LDValue.of(flagValue)).build();
        TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flag);

        try (LDClient ldClient = LDClient.init(application, config, ldContext, 1)) {
            assertTrue("client was not initialized", ldClient.isInitialized());
            assertTrue("client was offline", ldClient.isOffline());

            assertEquals(flagValue, ldClient.stringVariation(flagKey, "default"));
        }
    }

    @Test
    public void givenDefaultsAreNullAndTestOfflineClientReturnsDefaults() throws Exception {
        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(), ldContext, 1)) {
            assertTrue(ldClient.isInitialized());
            assertTrue(ldClient.isOffline());
            assertNull(ldClient.stringVariation("stringFlag", null));
            assertEquals(ldClient.jsonValueVariation("jsonFlag", null), LDValue.ofNull());
        }
    }

    @Test
    public void testInitMissingApplication() throws Exception {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        Future<LDClient> ldClientFuture = LDClient.init(null, makeOfflineConfig(), ldContext);

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
    public void testInitMissingConfig() throws Exception {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        Future<LDClient> ldClientFuture = LDClient.init(application, null, ldContext);

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
    public void testInitMissingContext() throws Exception {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        Future<LDClient> ldClientFuture = LDClient.init(application, makeOfflineConfig(), (LDContext)null);

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
    public void testDoubleClose() throws IOException {
        LDClient ldClient = LDClient.init(application, makeOfflineConfig(), ldContext, 1);
        ldClient.close();
        ldClient.close();
    }

    @Test
    public void testInitBackgroundThread() throws Exception {
        Future<?> backgroundComplete = Executors.newSingleThreadExecutor().submit(() -> {
            try {
                try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(), ldContext).get()) {
                    assertTrue(ldClient.isInitialized());
                    assertTrue(ldClient.isOffline());

                    assertTrue(ldClient.boolVariation("boolFlag", true));
                    assertEquals(1.0, ldClient.doubleVariation("floatFlag", 1.0), 0.0);
                    assertEquals(1, ldClient.intVariation("intFlag", 1));
                    assertEquals("default", ldClient.stringVariation("stringFlag", "default"));

                    LDValue expectedJson = LDValue.of("value");
                    assertEquals(expectedJson, ldClient.jsonValueVariation("jsonFlag", expectedJson));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        backgroundComplete.get();
    }

    private LDConfig makeOfflineConfig() {
        return new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(mobileKey)
                .offline(true)
                .build();
    }
}
