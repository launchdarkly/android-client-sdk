package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EnvironmentMetadata;
import com.launchdarkly.sdk.android.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.android.integrations.Hook;
import com.launchdarkly.sdk.android.integrations.IdentifySeriesContext;
import com.launchdarkly.sdk.android.integrations.IdentifySeriesResult;
import com.launchdarkly.sdk.android.integrations.Plugin;
import com.launchdarkly.sdk.android.integrations.PluginMetadata;
import com.launchdarkly.sdk.android.integrations.RegistrationCompleteResult;
import com.launchdarkly.sdk.android.integrations.TrackSeriesContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LDClientPluginsTest {

    private static final String mobileKey = "test-mobile-key";
    private static final String secondaryMobileKey = "test-secondary-mobile-key";
    private static final LDContext ldContext = LDContext.create("userKey");
    private Application application;

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void registerIsCalledForPlugins() throws Exception {

        MockHook testHook = new MockHook();
        MockPlugin testPlugin = new MockPlugin(Collections.singletonList(testHook));

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(List.of(testPlugin)), ldContext, 1)) {
            // This should get called because of an implicit identity.
            assertEquals(1, testHook.beforeIdentifyCalls.size());

            ldClient.boolVariation("test-flag", false);
            assertEquals(1, testPlugin.getHooksCalls.size());
            assertEquals(1, testPlugin.registerCalls.size());
            assertEquals(1, testHook.beforeEvaluationCalls.size());
            assertEquals(1, testHook.afterEvaluationCalls.size());

            EnvironmentMetadata environmentMetadata1 = (EnvironmentMetadata) testPlugin.getHooksCalls.get(0).get("environmentMetadata");
            assertEquals(mobileKey, environmentMetadata1.getCredential());
            assertEquals(environmentMetadata1, testPlugin.getHooksCalls.get(0).get("environmentMetadata"));
            assertEquals("AndroidClient", environmentMetadata1.getSdkMetadata().getName());

            assertEquals(ldClient, testPlugin.registerCalls.get(0).get("client"));
            EnvironmentMetadata environmentMetadata2 = (EnvironmentMetadata) testPlugin.registerCalls.get(0).get("environmentMetadata");
            assertEquals(mobileKey, environmentMetadata2.getCredential());
            assertEquals("AndroidClient", environmentMetadata2.getSdkMetadata().getName());

            logging.assertNoWarningsLogged();
            logging.assertNoErrorsLogged();
        }
    }

    @Test
    public void pluginRegisterCalledForEachClientEnvironment() throws Exception {
        MockHook testHook = new MockHook();
        MockPlugin testPlugin = new MockPlugin(Collections.singletonList(testHook));

        // create config with multiple mobile keys
        LDConfig.Builder builder = new LDConfig.Builder(LDConfig.Builder.AutoEnvAttributes.Disabled)
                .mobileKey(mobileKey)
                .secondaryMobileKeys(Map.of(
                        "secondaryEnvironment", secondaryMobileKey
                ))
                .plugins(Components.plugins().setPlugins(Collections.singletonList(testPlugin)))
                .offline(true)
                .events(Components.noEvents())
                .logAdapter(logging.logAdapter);
        LDConfig config = builder.build();

        try (LDClient ldClient = LDClient.init(application, config, ldContext, 10)) {
            ldClient.boolVariation("test-flag", false);
            assertEquals(2, testPlugin.getHooksCalls.size());
            assertEquals(2, testPlugin.registerCalls.size());
            assertEquals(1, testHook.beforeEvaluationCalls.size());
            assertEquals(1, testHook.afterEvaluationCalls.size());

            LDClient.getForMobileKey("secondaryEnvironment").boolVariation("test-flag", false);
            assertEquals(2, testHook.beforeEvaluationCalls.size());
            assertEquals(2, testHook.afterEvaluationCalls.size());

            for (Map<String, Object> hookCall: testPlugin.registerCalls) {
                LDClient instance = (LDClient) hookCall.get("client");

                if (instance.equals(LDClient.get())) {
                    EnvironmentMetadata environmentMetadata = (EnvironmentMetadata) hookCall.get("environmentMetadata");
                    assertEquals(environmentMetadata.getCredential(), mobileKey);
                } else if (instance.equals(LDClient.getForMobileKey("secondaryEnvironment"))) {
                    EnvironmentMetadata environmentMetadata = (EnvironmentMetadata) hookCall.get("environmentMetadata");
                    assertEquals(environmentMetadata.getCredential(), secondaryMobileKey);
                } else {
                    fail("Client instance was unexpected.");
                }
            }

            logging.assertNoWarningsLogged();
            logging.assertNoErrorsLogged();
        }
    }

    @Test
    public void identifyHooksRunForEachEnvironment() throws Exception {
        MockHook testHook = new MockHook();
        MockPlugin testPlugin = new MockPlugin(Collections.singletonList(testHook));

        LDConfig config = new LDConfig.Builder(LDConfig.Builder.AutoEnvAttributes.Disabled)
                .mobileKey(mobileKey)
                .secondaryMobileKeys(Map.of("secondaryEnvironment", secondaryMobileKey))
                .plugins(Components.plugins().setPlugins(Collections.singletonList(testPlugin)))
                .offline(true)
                .events(Components.noEvents())
                .logAdapter(logging.logAdapter)
                .build();

        try (LDClient ldClient = LDClient.init(application, config, ldContext, 10)) {
            IdentifySeriesContext initCtx = new IdentifySeriesContext(ldContext, null);
            IdentifySeriesResult completedResult = new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.COMPLETED);

            // After init: implicit identify fires once per instance (primary + secondary)
            assertEquals(2, testHook.beforeIdentifyCalls.size());
            assertEquals(initCtx, testHook.beforeIdentifyCalls.get(0).get("seriesContext"));
            assertEquals(initCtx, testHook.beforeIdentifyCalls.get(1).get("seriesContext"));
            assertEquals(2, testHook.afterIdentifyCalls.size());
            assertEquals(initCtx, testHook.afterIdentifyCalls.get(0).get("seriesContext"));
            assertEquals(completedResult, testHook.afterIdentifyCalls.get(0).get("result"));
            assertEquals(initCtx, testHook.afterIdentifyCalls.get(1).get("seriesContext"));
            assertEquals(completedResult, testHook.afterIdentifyCalls.get(1).get("result"));

            LDContext newContext = LDContext.create("newUserKey");
            IdentifySeriesContext newCtx = new IdentifySeriesContext(newContext, null);
            ldClient.identify(newContext).get();

            // After identifying on the primary client: +1
            assertEquals(3, testHook.beforeIdentifyCalls.size());
            assertEquals(newCtx, testHook.beforeIdentifyCalls.get(2).get("seriesContext"));
            assertEquals(3, testHook.afterIdentifyCalls.size());
            assertEquals(newCtx, testHook.afterIdentifyCalls.get(2).get("seriesContext"));
            assertEquals(completedResult, testHook.afterIdentifyCalls.get(2).get("result"));

            LDClient.getForMobileKey("secondaryEnvironment").identify(newContext).get();

            // After identifying on the secondary client: +1
            assertEquals(4, testHook.beforeIdentifyCalls.size());
            assertEquals(newCtx, testHook.beforeIdentifyCalls.get(3).get("seriesContext"));
            assertEquals(4, testHook.afterIdentifyCalls.size());
            assertEquals(newCtx, testHook.afterIdentifyCalls.get(3).get("seriesContext"));
            assertEquals(completedResult, testHook.afterIdentifyCalls.get(3).get("result"));

            logging.assertNoWarningsLogged();
            logging.assertNoErrorsLogged();
        }
    }

    @Test
    public void configuredPluginThatFailsToRegisterContributesNoHooks() throws Exception {
        MockHook testHook = new MockHook();
        MockPlugin testPlugin = new MockPlugin(Collections.singletonList(testHook), false, true);

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(List.of(testPlugin)), ldContext, 1)) {
            assertEquals(1, testPlugin.registerCalls.size());

            // The hooks go live only once register has succeeded, so this plugin contributes none.
            ldClient.boolVariation("test-flag", false);
            assertEquals(0, testHook.beforeEvaluationCalls.size());
            assertEquals(0, testHook.afterEvaluationCalls.size());

            assertEquals(1, testPlugin.onPluginsReadyCalls.size());
            RegistrationCompleteResult result =
                    (RegistrationCompleteResult) testPlugin.onPluginsReadyCalls.get(0).get("result");
            assertTrue(result instanceof RegistrationCompleteResult.Failure);

            logging.assertErrorLogged("Exception thrown registering plugin");
        }
    }

    @Test
    public void configuredPluginWhoseGetHooksThrowsIsNotRegistered() throws Exception {
        MockHook testHook = new MockHook();
        MockPlugin testPlugin = new MockPlugin(Collections.singletonList(testHook), true, false);

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(List.of(testPlugin)), ldContext, 1)) {
            // The logged message says the plugin will not be registered, and it is not.
            assertEquals(0, testPlugin.registerCalls.size());

            ldClient.boolVariation("test-flag", false);
            assertEquals(0, testHook.beforeEvaluationCalls.size());

            logging.assertErrorLogged("Unable to get hooks");
        }
    }

    @Test
    public void configuredPluginHooksDoNotObserveAnotherPluginsRegister() throws Exception {
        MockHook firstHook = new MockHook();
        MockPlugin firstPlugin = new MockPlugin(Collections.singletonList(firstHook));
        // Registers after the first plugin, and evaluates a flag while doing so.
        EvaluateOnRegisterPlugin secondPlugin = new EvaluateOnRegisterPlugin(new MockHook());

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(List.of(firstPlugin, secondPlugin)), ldContext, 1)) {
            assertEquals(1, secondPlugin.registerCalls.size());

            // Hooks are activated only once every plugin has registered, so the first plugin's hooks
            // did not observe the evaluation the second plugin made while registering.
            assertEquals(0, firstHook.beforeEvaluationCalls.size());

            ldClient.boolVariation("test-flag", false);
            assertEquals(1, firstHook.beforeEvaluationCalls.size());

            logging.assertNoErrorsLogged();
        }
    }

    @Test
    public void configuredPluginFailureDoesNotPreventOtherPlugins() throws Exception {
        MockHook goodHook = new MockHook();
        MockPlugin badPlugin = new MockPlugin(Collections.emptyList(), false, true);
        MockPlugin goodPlugin = new MockPlugin(Collections.singletonList(goodHook));

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(List.of(badPlugin, goodPlugin)), ldContext, 1)) {
            assertEquals(1, goodPlugin.registerCalls.size());

            ldClient.boolVariation("test-flag", false);
            assertEquals(1, goodHook.beforeEvaluationCalls.size());

            logging.assertErrorLogged("Exception thrown registering plugin");
        }
    }

    @Test
    public void registerPluginPassesClientAndEnvironmentMetadata() throws Exception {
        MockPlugin testPlugin = new MockPlugin(Collections.emptyList());

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(null), ldContext, 1)) {
            ldClient.registerPlugin(testPlugin);

            assertEquals(1, testPlugin.getHooksCalls.size());
            assertEquals(1, testPlugin.registerCalls.size());
            assertEquals(ldClient, testPlugin.registerCalls.get(0).get("client"));

            EnvironmentMetadata metadata = (EnvironmentMetadata) testPlugin.registerCalls.get(0).get("environmentMetadata");
            assertEquals(mobileKey, metadata.getCredential());
            assertEquals("AndroidClient", metadata.getSdkMetadata().getName());

            // The same environment description a plugin configured up front would have been given.
            assertEquals(metadata, testPlugin.getHooksCalls.get(0).get("environmentMetadata"));

            logging.assertNoErrorsLogged();
        }
    }

    @Test
    public void registerPluginActivatesBundledHooks() throws Exception {
        MockHook testHook = new MockHook();
        MockPlugin testPlugin = new MockPlugin(Collections.singletonList(testHook));

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(null), ldContext, 1)) {
            ldClient.registerPlugin(testPlugin);

            ldClient.boolVariation("test-flag", false);
            assertEquals(1, testHook.beforeEvaluationCalls.size());
            assertEquals(1, testHook.afterEvaluationCalls.size());

            ldClient.identify(LDContext.create("newUserKey")).get();
            // Only the identify made after registration: the implicit one during init predates the plugin.
            assertEquals(1, testHook.beforeIdentifyCalls.size());
            assertEquals(1, testHook.afterIdentifyCalls.size());

            ldClient.track("test-event");
            assertEquals(1, testHook.afterTrackCalls.size());

            logging.assertNoErrorsLogged();
        }
    }

    @Test
    public void registerPluginDoesNotRunTheRegisteringPluginsOwnHooks() throws Exception {
        MockHook testHook = new MockHook();
        EvaluateOnRegisterPlugin testPlugin = new EvaluateOnRegisterPlugin(testHook);

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(null), ldContext, 1)) {
            ldClient.registerPlugin(testPlugin);

            // The plugin evaluated a flag from inside register, but its own hooks only go live once
            // register has returned, as they do for a plugin configured up front.
            assertEquals(1, testPlugin.registerCalls.size());
            assertEquals(0, testHook.beforeEvaluationCalls.size());
            assertEquals(0, testHook.afterEvaluationCalls.size());

            // They do run for evaluations made once registration has completed.
            ldClient.boolVariation("test-flag", false);
            assertEquals(1, testHook.beforeEvaluationCalls.size());
            assertEquals(1, testHook.afterEvaluationCalls.size());

            logging.assertNoErrorsLogged();
        }
    }

    @Test
    public void registerPluginDoesNotCallOnPluginsReady() throws Exception {
        MockPlugin testPlugin = new MockPlugin(Collections.emptyList());

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(null), ldContext, 1)) {
            ldClient.registerPlugin(testPlugin);

            // onPluginsReady reports on a batch of plugins registered together, so registering a
            // single plugin on its own has nothing to report and does not call it.
            assertEquals(1, testPlugin.registerCalls.size());
            assertEquals(0, testPlugin.onPluginsReadyCalls.size());

            logging.assertNoErrorsLogged();
        }
    }

    @Test
    public void registerPluginDoesNotRegisterPluginWhoseGetHooksThrows() throws Exception {
        MockHook testHook = new MockHook();
        MockPlugin testPlugin = new MockPlugin(Collections.singletonList(testHook), true, false);

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(null), ldContext, 1)) {
            ldClient.registerPlugin(testPlugin);

            assertEquals(0, testPlugin.registerCalls.size());
            assertEquals(0, testPlugin.onPluginsReadyCalls.size());

            ldClient.boolVariation("test-flag", false);
            assertEquals(0, testHook.beforeEvaluationCalls.size());

            logging.assertErrorLogged("Unable to get hooks");
        }
    }

    @Test
    public void registerPluginToleratesRegisterThrowing() throws Exception {
        MockHook testHook = new MockHook();
        MockPlugin testPlugin = new MockPlugin(Collections.singletonList(testHook), false, true);

        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(null), ldContext, 1)) {
            // The exception is logged rather than propagated.
            ldClient.registerPlugin(testPlugin);
            assertEquals(1, testPlugin.registerCalls.size());

            // A plugin that failed to register contributes no hooks, as one configured up front
            // whose register throws now also does not.
            ldClient.boolVariation("test-flag", false);
            assertEquals(0, testHook.beforeEvaluationCalls.size());
            assertEquals(0, testHook.afterEvaluationCalls.size());

            // The failure is reported in the log alone, since this path does not call
            // onPluginsReady.
            assertEquals(0, testPlugin.onPluginsReadyCalls.size());

            logging.assertErrorLogged("Exception thrown registering plugin");
        }
    }

    @Test
    public void registerPluginRejectsNullPlugin() throws Exception {
        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(null), ldContext, 1)) {
            assertThrows(NullPointerException.class, () -> ldClient.registerPlugin(null));
        }
    }

    @Test
    public void registerPluginAppliesOnlyToTheClientItIsCalledOn() throws Exception {
        MockHook testHook = new MockHook();
        MockPlugin testPlugin = new MockPlugin(Collections.singletonList(testHook));

        LDConfig config = new LDConfig.Builder(LDConfig.Builder.AutoEnvAttributes.Disabled)
                .mobileKey(mobileKey)
                .secondaryMobileKeys(Map.of("secondaryEnvironment", secondaryMobileKey))
                .offline(true)
                .events(Components.noEvents())
                .logAdapter(logging.logAdapter)
                .build();

        try (LDClient ldClient = LDClient.init(application, config, ldContext, 10)) {
            ldClient.registerPlugin(testPlugin);

            assertEquals(1, testPlugin.registerCalls.size());
            assertEquals(ldClient, testPlugin.registerCalls.get(0).get("client"));

            ldClient.boolVariation("test-flag", false);
            assertEquals(1, testHook.beforeEvaluationCalls.size());

            // The other environment has its own client, which this plugin was not registered with.
            LDClient.getForMobileKey("secondaryEnvironment").boolVariation("test-flag", false);
            assertEquals(1, testHook.beforeEvaluationCalls.size());

            logging.assertNoErrorsLogged();
        }
    }

    private LDConfig makeOfflineConfig(List<Plugin> plugins) {
        LDConfig.Builder builder = new LDConfig.Builder(LDConfig.Builder.AutoEnvAttributes.Disabled)
                .mobileKey(mobileKey)
                .offline(true)
                .events(Components.noEvents())
                .logAdapter(logging.logAdapter);

        if (plugins != null) {
            builder.plugins(Components.plugins().setPlugins(plugins));
        }

        return builder.build();
    }

    private static class MockPlugin extends Plugin {

        private final List<Hook> hooks;
        private final boolean throwOnGetHooks;
        private final boolean throwOnRegister;

        public final List<Map<String, Object>> getHooksCalls = new ArrayList<>();
        public final List<Map<String, Object>> registerCalls = new ArrayList<>();
        public final List<Map<String, Object>> onPluginsReadyCalls = new ArrayList<>();

        public MockPlugin(List<Hook> hooks) {
            this(hooks, false, false);
        }

        public MockPlugin(List<Hook> hooks, boolean throwOnGetHooks, boolean throwOnRegister) {
            this.hooks = hooks;
            this.throwOnGetHooks = throwOnGetHooks;
            this.throwOnRegister = throwOnRegister;
        }

        @NonNull
        @Override
        public PluginMetadata getMetadata() {
            return new PluginMetadata() {
                @NonNull
                @Override
                public String getName() {
                    return "mock-plugin-name";
                }

                @NonNull
                @Override
                public String getVersion() {
                    return "mock-plugin-version";
                }

                @NonNull
                @Override
                public String getId() {
                    return "mock-plugin-id";
                }
            };
        }

        @Override
        public void register(LDClient client, EnvironmentMetadata metadata) {
            registerCalls.add(Map.of(
                    "client", client,
                    "environmentMetadata", metadata
            ));
            if (throwOnRegister) {
                throw new RuntimeException("register failed for mock-plugin-name");
            }
        }

        @NonNull
        @Override
        public List<Hook> getHooks(EnvironmentMetadata metadata) {
            getHooksCalls.add(Map.of(
                    "environmentMetadata", metadata
            ));
            if (throwOnGetHooks) {
                throw new RuntimeException("getHooks failed for mock-plugin-name");
            }
            return this.hooks;
        }

        // Overridden despite the deprecation so tests can assert both that the configured-plugin
        // path still calls it and that registerPlugin does not.
        @SuppressWarnings("deprecation")
        @Override
        public void onPluginsReady(RegistrationCompleteResult result, EnvironmentMetadata metadata) {
            onPluginsReadyCalls.add(Map.of(
                    "result", result,
                    "environmentMetadata", metadata
            ));
        }
    }

    /**
     * Evaluates a flag from inside {@code register}, so a test can tell whether the plugin's own hooks were live at
     * that point.
     */
    private static class EvaluateOnRegisterPlugin extends Plugin {

        private final Hook hook;

        public final List<Map<String, Object>> registerCalls = new ArrayList<>();

        public EvaluateOnRegisterPlugin(Hook hook) {
            this.hook = hook;
        }

        @NonNull
        @Override
        public PluginMetadata getMetadata() {
            return new PluginMetadata() {
                @NonNull
                @Override
                public String getName() {
                    return "evaluate-on-register-plugin";
                }
            };
        }

        @Override
        public void register(LDClient client, EnvironmentMetadata metadata) {
            registerCalls.add(Map.of("client", client, "environmentMetadata", metadata));
            client.boolVariation("test-flag", false);
        }

        @NonNull
        @Override
        public List<Hook> getHooks(EnvironmentMetadata metadata) {
            return Collections.singletonList(hook);
        }
    }

    private static class MockHook extends Hook {
        public final List<Map<String, Object>> beforeEvaluationCalls = new ArrayList<>();
        public final List<Map<String, Object>> afterEvaluationCalls = new ArrayList<>();
        public final List<Map<String, Object>> beforeIdentifyCalls = new ArrayList<>();
        public final List<Map<String, Object>> afterIdentifyCalls = new ArrayList<>();
        public final List<Map<String, Object>> afterTrackCalls = new ArrayList<>();

        public MockHook() {
            super("MockHook");
        }

        @Override
        public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
            beforeEvaluationCalls.add(Map.of(
                    "seriesContext", seriesContext,
                    "seriesData", seriesData
            ));
            return Collections.unmodifiableMap(Collections.emptyMap());
        }

        @Override
        public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData, EvaluationDetail<LDValue> evaluationDetail) {
            afterEvaluationCalls.add(Map.of(
                    "seriesContext", seriesContext,
                    "seriesData", seriesData,
                    "evaluationDetail", evaluationDetail
            ));
            return Collections.unmodifiableMap(Collections.emptyMap());
        }

        @Override
        public Map<String, Object> beforeIdentify(IdentifySeriesContext seriesContext, Map<String, Object> seriesData) {
            beforeIdentifyCalls.add(Map.of(
                    "seriesContext", seriesContext,
                    "seriesData", seriesData
            ));
            return Collections.unmodifiableMap(Collections.emptyMap());
        }

        @Override
        public Map<String, Object> afterIdentify(IdentifySeriesContext seriesContext, Map<String, Object> seriesData, IdentifySeriesResult result) {
            afterIdentifyCalls.add(Map.of(
                    "seriesContext", seriesContext,
                    "seriesData", seriesData,
                    "result", result
            ));
            return Collections.unmodifiableMap(Collections.emptyMap());
        }

        @Override
        public void afterTrack(TrackSeriesContext seriesContext) {
            afterTrackCalls.add(Map.of(
                    "seriesContext", seriesContext
            ));
        }
    }
}
