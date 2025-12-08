package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
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

        public final List<Map<String, Object>> getHooksCalls = new ArrayList<>();
        public final List<Map<String, Object>> registerCalls = new ArrayList<>();

        public MockPlugin(List<Hook> hooks) {
            this.hooks = hooks;
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
            };
        }

        @Override
        public void register(LDClient client, EnvironmentMetadata metadata) {
            registerCalls.add(Map.of(
                    "client", client,
                    "environmentMetadata", metadata
            ));
        }

        @NonNull
        @Override
        public List<Hook> getHooks(EnvironmentMetadata metadata) {
            getHooksCalls.add(Map.of(
                    "environmentMetadata", metadata
            ));
            return this.hooks;
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
