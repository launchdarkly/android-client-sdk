package com.launchdarkly.sdk.android;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class LDClientTest {

    private static final String mobileKey = "test-mobile-key";
    private Application application;
    private LDClient ldClient;
    private Future<LDClient> ldClientFuture;
    private LDConfig ldConfig;
    private LDContext ldUser;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        ldConfig = new LDConfig.Builder()
                .mobileKey(mobileKey)
                .offline(true)
                .build();

        ldUser = LDContext.create("userKey");
    }

    @Test
    public void testOfflineClientReturnsDefaultsIfThereAreNoStoredFlags() {
        ldClient = LDClient.init(application, ldConfig, ldUser, 1);

        assertTrue("client was not initialized", ldClient.isInitialized());
        assertTrue("client was offline", ldClient.isOffline());

        assertTrue(ldClient.boolVariation("boolFlag", true));
        assertEquals(1.5, ldClient.doubleVariation("doubleFlag", 1.5), 0.0);
        assertEquals(1, ldClient.intVariation("intFlag", 1));
        assertEquals("default", ldClient.stringVariation("stringFlag", "default"));

        LDValue expectedJson = LDValue.of("value");
        assertEquals(expectedJson, ldClient.jsonValueVariation("jsonFlag", expectedJson));
    }

    @Test
    public void testOfflineClientUsesStoredFlags() {
        PersistentDataStore store = new InMemoryPersistentDataStore();
        LDConfig config = new LDConfig.Builder()
                .mobileKey(mobileKey)
                .offline(true)
                .persistentDataStore(store)
                .build();

        String flagKey = "flag-key", flagValue = "stored-value";
        Flag flag = new FlagBuilder(flagKey).version(1).value(LDValue.of(flagValue)).build();
        TestUtil.writeFlagUpdateToStore(store, mobileKey, ldUser, flag);

        ldClient = LDClient.init(application, config, ldUser, 1);

        assertTrue("client was not initialized", ldClient.isInitialized());
        assertTrue("client was offline", ldClient.isOffline());

        assertEquals(flagValue, ldClient.stringVariation(flagKey, "default"));
    }

    @Test
    public void givenDefaultsAreNullAndTestOfflineClientReturnsDefaults() {
        ldClient = LDClient.init(application, ldConfig, ldUser, 1);

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());
        assertNull(ldClient.stringVariation("stringFlag", null));
        assertEquals(ldClient.jsonValueVariation("jsonFlag", null), LDValue.ofNull());
    }

    @Test
    public void testInitMissingApplication() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        ldClientFuture = LDClient.init(null, ldConfig, ldUser);

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

        ldClientFuture = LDClient.init(application, null, ldUser);

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
    public void testInitMissingUser() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        ldClientFuture = LDClient.init(application, ldConfig, null);

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

    // the second ldClient.close() should throw a npe
    public void testDoubleClose() throws IOException {
        ldClient = LDClient.init(application, ldConfig, ldUser, 1);
        ldClient.close();
        ldClient.close();
    }

    @Test
    public void testInitBackgroundThread() throws ExecutionException, InterruptedException {
        Future<?> backgroundComplete = new BackgroundThreadExecutor().newFixedThreadPool(1).submit(() -> {
            try {
                ldClient = LDClient.init(application, ldConfig, ldUser).get();
            } catch (Exception e) {
                fail();
            }
            assertTrue(ldClient.isInitialized());
            assertTrue(ldClient.isOffline());

            assertTrue(ldClient.boolVariation("boolFlag", true));
            assertEquals(1.0, ldClient.doubleVariation("floatFlag", 1.0), 0.0);
            assertEquals(1, ldClient.intVariation("intFlag", 1));
            assertEquals("default", ldClient.stringVariation("stringFlag", "default"));

            LDValue expectedJson = LDValue.of("value");
            assertEquals(expectedJson, ldClient.jsonValueVariation("jsonFlag", expectedJson));
        });
        backgroundComplete.get();
    }

    @Test
    public void clientStartsOfflineWithStoredFlags() {
        Flag flag = new FlagBuilder("flag-key").version(1).value(LDValue.of("yes")).build();
        PersistentDataStore store = new InMemoryPersistentDataStore();
        TestUtil.writeFlagUpdateToStore(store, mobileKey, ldUser, flag);

        LDConfig config = new LDConfig.Builder()
                .mobileKey(mobileKey)
                .offline(true)
                .persistentDataStore(store)
                .build();
        ldClient = LDClient.init(application, config, ldUser, 1);

        assertTrue("client was not initialized", ldClient.isInitialized());
        assertTrue("client was offline", ldClient.isOffline());

        assertEquals("yes", ldClient.stringVariation(flag.getKey(), "default"));
    }
}
