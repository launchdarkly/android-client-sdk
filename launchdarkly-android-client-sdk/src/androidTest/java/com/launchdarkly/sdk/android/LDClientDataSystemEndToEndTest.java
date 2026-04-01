package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.URI;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * End-to-end tests for LDClient configured with the FDv2 data system
 * ({@link Components#dataSystem()}). These tests use {@link MockWebServer}
 * to simulate FDv2 polling responses.
 * <p>
 * Comprehensive FDv2 protocol coverage is intended for the sdk-test-harness
 * integration.
 */
@RunWith(AndroidJUnit4.class)
public class LDClientDataSystemEndToEndTest {
    private static final String MOBILE_KEY = "test-mobile-key";
    private static final LDContext CONTEXT = LDContext.create("context");

    private Application application;
    private MockWebServer mockPollingServer;
    private URI mockPollingServerUri;
    private PersistentDataStore store;

    @Rule
    public final ActivityScenarioRule<TestActivity> testScenario =
            new ActivityScenarioRule<>(TestActivity.class);

    @Rule
    public AndroidLoggingRule logging = new AndroidLoggingRule();

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();

        AndroidTestUtil.doSynchronouslyOnMainThreadForTestScenario(testScenario,
                act -> {
                    mockPollingServer = new MockWebServer();
                    try {
                        mockPollingServer.start();
                    } catch (IOException err) {
                        throw new RuntimeException(err);
                    }
                    mockPollingServerUri = mockPollingServer.url("/").uri();
                });
    }

    @Before
    public void before() {
        store = new InMemoryPersistentDataStore();
    }

    @After
    public void after() throws IOException {
        mockPollingServer.close();
        testScenario.getScenario().close();
    }

    private LDConfig.Builder baseConfig() {
        return new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(MOBILE_KEY)
                .persistentDataStore(store)
                .diagnosticOptOut(true)
                .events(Components.noEvents())
                .logAdapter(logging.logAdapter)
                .loggerName(logging.loggerName)
                .logLevel(LDLogLevel.DEBUG);
    }

    @Test
    public void clientStartsWithDataSystemPollingAndEvaluatesFlag() throws Exception {
        String flagKey = "flag-key", flagValue = "good-value";
        Flag flag = new FlagBuilder(flagKey).version(1).value(LDValue.of(flagValue)).build();

        String fdv2Body = FDv2TestResponses.pollResponseBody(flag);
        mockPollingServer.enqueue(new MockResponse().setResponseCode(200).setBody(fdv2Body));

        LDConfig config = baseConfig()
                .dataSystem(
                        Components.dataSystem()
                                .foregroundConnectionMode(ConnectionMode.POLLING))
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(application, config, CONTEXT, 30)) {
            assertTrue("client was not initialized", client.isInitialized());
            assertFalse("client was offline", client.isOffline());

            assertEquals(flagValue, client.stringVariation(flagKey, "thisDefaultShouldNotBeSeen"));
        }
    }

    @Test
    public void clientUsesStoredFlagsIfInitializationFailsWithDataSystemPolling() throws Exception {
        String flagKey = "flag-key", flagValue = "stored-value";
        Flag flag = new FlagBuilder(flagKey).version(1).value(LDValue.of(flagValue)).build();
        TestUtil.writeFlagUpdateToStore(store, MOBILE_KEY, CONTEXT, flag);

        mockPollingServer.enqueue(new MockResponse().setResponseCode(401));

        LDConfig config = baseConfig()
                .dataSystem(
                        Components.dataSystem()
                                .foregroundConnectionMode(ConnectionMode.POLLING))
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(application, config, CONTEXT, 1)) {
            assertFalse("client should not have been initialized", client.isInitialized());
            assertFalse("client was offline", client.isOffline());

            assertEquals(flagValue, client.stringVariation(flagKey, "thisDefaultShouldNotBeSeen"));
        }
    }

    @Test
    public void identifyWhenDataSystemPollingFailsAndCacheAlreadyExists() throws Exception {
        LDContext contextA = LDContext.create("ContextA");
        String flagKeyA = "flag-keyA", flagValueA = "stored-valueA";
        Flag flagA = new FlagBuilder(flagKeyA).version(1).value(LDValue.of(flagValueA)).build();
        TestUtil.writeFlagUpdateToStore(store, MOBILE_KEY, contextA, flagA);

        LDContext contextB = LDContext.create("ContextB");
        String flagKeyB = "flag-keyB", flagValueB = "stored-valueB";
        Flag flagB = new FlagBuilder(flagKeyB).version(1).value(LDValue.of(flagValueB)).build();
        TestUtil.writeFlagUpdateToStore(store, MOBILE_KEY, contextB, flagB);

        mockPollingServer.enqueue(new MockResponse().setResponseCode(401));
        mockPollingServer.enqueue(new MockResponse().setResponseCode(401));

        LDConfig config = baseConfig()
                .dataSystem(
                        Components.dataSystem()
                                .foregroundConnectionMode(ConnectionMode.POLLING))
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(application, config, contextA, 1)) {
            assertFalse("client should not have been initialized", client.isInitialized());
            assertFalse("client was offline", client.isOffline());
            assertEquals(flagValueA, client.stringVariation(flagKeyA, "defaultA"));

            client.identify(contextB).get();
            assertEquals(flagValueB, client.stringVariation(flagKeyB, "defaultB"));
        }
    }
}
