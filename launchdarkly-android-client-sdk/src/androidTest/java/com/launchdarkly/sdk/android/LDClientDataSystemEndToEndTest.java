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
import com.launchdarkly.sdk.android.integrations.AutomaticModeSwitchingConfig;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * End-to-end tests for LDClient configured with the FDv2 data system
 * ({@link Components#dataSystem()}). These tests use {@link MockWebServer}
 * to simulate FDv2 polling and streaming responses.
 * <p>
 * Comprehensive FDv2 protocol coverage is intended for the sdk-test-harness
 * integration.
 */
@RunWith(AndroidJUnit4.class)
public class LDClientDataSystemEndToEndTest {
    private static final String MOBILE_KEY = "test-mobile-key";
    private static final LDContext CONTEXT = LDContext.create("context");

    /** Timeout for stream-delivered partial updates (no polling-interval delay). */
    private static final int STREAM_PARTIAL_TEST_TIMEOUT_MS = 15_000;

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

    private static MockResponse sseResponse(String sseBody) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody(sseBody);
    }

    /**
     * JSON for a {@code flag-eval} object that omits {@code version}, derived from
     * {@link Flag#toJson()} so deserialization matches the full-transfer shape.
     */
    private static String flagEvalObjectJsonWithoutVersion(Flag flagWithDesiredValue) {
        JsonObject o = JsonParser.parseString(flagWithDesiredValue.toJson()).getAsJsonObject();
        o.remove("version");
        return o.toString();
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

    @Test
    public void partialXferChangesNotifiesFeatureFlagListener() throws Exception {
        String flagKey = "flag-key-listener";
        String before = "before";
        String after = "after";
        Flag flagInitial = new FlagBuilder(flagKey).version(1).value(LDValue.of(before)).build();
        Flag flagUpdatedShape = new FlagBuilder(flagKey).version(999).value(LDValue.of(after)).build();
        String partialObjectJson = flagEvalObjectJsonWithoutVersion(flagUpdatedShape);

        String sseFullOnly = FDv2TestResponses.streamingSseBodyXferFullOnly(flagInitial, flagKey);
        String ssePartialOnly =
                FDv2TestResponses.streamingSseBodyXferChangesPartialOnly(
                        flagKey, 376, partialObjectJson);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        AtomicInteger streamConnections = new AtomicInteger(0);
        mockPollingServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!request.getPath().startsWith("/sdk/stream/eval")) {
                    return new MockResponse().setResponseCode(404);
                }
                if (streamConnections.getAndIncrement() == 0) {
                    return sseResponse(sseFullOnly);
                }
                try {
                    releaseUpdate.await(STREAM_PARTIAL_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return sseResponse(ssePartialOnly);
            }
        });

        LDConfig config = baseConfig()
                .dataSystem(
                        Components.dataSystem()
                                .foregroundConnectionMode(ConnectionMode.STREAMING)
                                .automaticModeSwitching(AutomaticModeSwitchingConfig.disabled())
                                .customizeConnectionMode(
                                        ConnectionMode.STREAMING,
                                        DataSystemComponents.customMode()
                                                .synchronizers(
                                                        DataSystemComponents.streamingSynchronizer())))
                .serviceEndpoints(
                        Components.serviceEndpoints()
                                .streaming(mockPollingServerUri)
                                .polling(mockPollingServerUri))
                .build();

        CountDownLatch listenerFired = new CountDownLatch(1);
        try (LDClient client = LDClient.init(application, config, CONTEXT, 30)) {
            assertTrue(client.isInitialized());
            assertEquals(before, client.stringVariation(flagKey, "default-unset"));

            client.registerFeatureFlagListener(
                    flagKey,
                    key -> {
                        if (after.equals(client.stringVariation(key, "default-unset"))) {
                            listenerFired.countDown();
                        }
                    });

            releaseUpdate.countDown();

            assertTrue(
                    "listener should fire after partial xfer-changes",
                    listenerFired.await(STREAM_PARTIAL_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(after, client.stringVariation(flagKey, "default-unset"));
        }
    }
}
