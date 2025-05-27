package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.android.integrations.Hook;
import com.launchdarkly.sdk.android.integrations.IdentifySeriesContext;
import com.launchdarkly.sdk.android.integrations.IdentifySeriesResult;
import com.launchdarkly.sdk.android.integrations.TrackSeriesContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LDClientHooksTest {

    private static final String mobileKey = "test-mobile-key";
    private static final LDContext ldContext = LDContext.create("userKey");
    private Application application;
    private MockHook testHook;

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        testHook = new MockHook();
    }

    @Test
    public void executesHooksRegisteredDuringConfiguration() throws Exception {
        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(List.of(testHook)), ldContext, 1)) {
            ldClient.boolVariation("test-flag", false);

            EvaluationSeriesContext evaluationSeriesContext = new EvaluationSeriesContext("LDClient.boolVariation", "test-flag", ldContext, LDValue.of(false));
            EvaluationDetail<LDValue> evaluationDetail = EvaluationDetail.fromValue(LDValue.of(false), -1, EvaluationReason.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND));

            assertEquals(1, testHook.beforeEvaluationCalls.size());
            assertEquals(evaluationSeriesContext, testHook.beforeEvaluationCalls.get(0).get("seriesContext"));
            assertEquals(1, testHook.afterEvaluationCalls.size());
            assertEquals(evaluationSeriesContext, testHook.afterEvaluationCalls.get(0).get("seriesContext"));
            assertEquals(evaluationDetail, testHook.afterEvaluationCalls.get(0).get("evaluationDetail"));

            LDContext newContext = LDContext.create("newUserKey");
            ldClient.identify(newContext).get();

            IdentifySeriesContext identifySeriesContext = new IdentifySeriesContext(newContext, null);
            IdentifySeriesResult identifySeriesResult = new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.COMPLETED);

            assertEquals(1, testHook.beforeIdentifyCalls.size());
            assertEquals(identifySeriesContext, testHook.beforeIdentifyCalls.get(0).get("seriesContext"));
            assertEquals(1, testHook.afterIdentifyCalls.size());
            assertEquals(identifySeriesContext, testHook.afterIdentifyCalls.get(0).get("seriesContext"));
            assertEquals(identifySeriesResult, testHook.afterIdentifyCalls.get(0).get("result"));

            ldClient.trackMetric("test-event", LDValue.buildObject().put("data", "test").build(), 123.45);

            TrackSeriesContext trackSeriesContext = new TrackSeriesContext("test-event", newContext, LDValue.buildObject().put("data", "test").build(), 123.45);

            assertEquals(1, testHook.afterTrackCalls.size());
            assertEquals(trackSeriesContext, testHook.afterTrackCalls.get(0).get("seriesContext"));

            logging.assertNoWarningsLogged();
            logging.assertNoErrorsLogged();
        }
    }

    @Test
    public void executesHooksAddedWithAddHooks() throws Exception {
        MockHook addedHook = new MockHook();
        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(), ldContext, 1)) {
            ldClient.addHook(addedHook);

            ldClient.boolVariation("test-flag", false);

            EvaluationSeriesContext evaluationSeriesContext = new EvaluationSeriesContext("LDClient.boolVariation", "test-flag", ldContext, LDValue.of(false));
            EvaluationDetail<LDValue> evaluationDetail = EvaluationDetail.fromValue(LDValue.of(false), -1, EvaluationReason.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND));

            assertEquals(1, addedHook.beforeEvaluationCalls.size());
            assertEquals(evaluationSeriesContext, addedHook.beforeEvaluationCalls.get(0).get("seriesContext"));
            assertEquals(1, addedHook.afterEvaluationCalls.size());
            assertEquals(evaluationSeriesContext, addedHook.afterEvaluationCalls.get(0).get("seriesContext"));
            assertEquals(evaluationDetail, addedHook.afterEvaluationCalls.get(0).get("evaluationDetail"));

            LDContext newContext = LDContext.create("newUserKey");
            ldClient.identify(newContext).get();

            IdentifySeriesContext identifySeriesContext = new IdentifySeriesContext(newContext, null);
            IdentifySeriesResult identifySeriesResult = new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.COMPLETED);

            assertEquals(1, addedHook.beforeIdentifyCalls.size());
            assertEquals(identifySeriesContext, addedHook.beforeIdentifyCalls.get(0).get("seriesContext"));
            assertEquals(1, addedHook.afterIdentifyCalls.size());
            assertEquals(identifySeriesContext, addedHook.afterIdentifyCalls.get(0).get("seriesContext"));
            assertEquals(identifySeriesResult, addedHook.afterIdentifyCalls.get(0).get("result"));

            ldClient.trackMetric("test-event", LDValue.buildObject().put("data", "test").build(), 123.45);

            TrackSeriesContext trackSeriesContext = new TrackSeriesContext("test-event", newContext, LDValue.buildObject().put("data", "test").build(), 123.45);

            assertEquals(1, addedHook.afterTrackCalls.size());
            assertEquals(trackSeriesContext, addedHook.afterTrackCalls.get(0).get("seriesContext"));

            logging.assertNoWarningsLogged();
            logging.assertNoErrorsLogged();
        }
    }

    @Test
    public void executesBothInitialHooksAndHooksAddedWithAddHooks() throws Exception {
        MockHook addedHook = new MockHook();
        try (LDClient ldClient = LDClient.init(application, makeOfflineConfig(List.of(testHook)), ldContext, 1)) {
            ldClient.addHook(addedHook);

            ldClient.boolVariation("test-flag", false);

            EvaluationSeriesContext evaluationSeriesContext = new EvaluationSeriesContext("LDClient.boolVariation", "test-flag", ldContext, LDValue.of(false));
            EvaluationDetail<LDValue> evaluationDetail = EvaluationDetail.fromValue(LDValue.of(false), -1, EvaluationReason.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND));

            assertEquals(1, testHook.beforeEvaluationCalls.size());
            assertEquals(evaluationSeriesContext, testHook.beforeEvaluationCalls.get(0).get("seriesContext"));
            assertEquals(1, testHook.afterEvaluationCalls.size());
            assertEquals(evaluationSeriesContext, testHook.afterEvaluationCalls.get(0).get("seriesContext"));
            assertEquals(evaluationDetail, testHook.afterEvaluationCalls.get(0).get("evaluationDetail"));

            assertEquals(1, addedHook.beforeEvaluationCalls.size());
            assertEquals(evaluationSeriesContext, addedHook.beforeEvaluationCalls.get(0).get("seriesContext"));
            assertEquals(1, addedHook.afterEvaluationCalls.size());
            assertEquals(evaluationSeriesContext, addedHook.afterEvaluationCalls.get(0).get("seriesContext"));
            assertEquals(evaluationDetail, addedHook.afterEvaluationCalls.get(0).get("evaluationDetail"));

            LDContext newContext = LDContext.create("newUserKey");
            ldClient.identify(newContext).get();

            IdentifySeriesContext identifySeriesContext = new IdentifySeriesContext(newContext, null);
            IdentifySeriesResult identifySeriesResult = new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.COMPLETED);

            assertEquals(1, testHook.beforeIdentifyCalls.size());
            assertEquals(identifySeriesContext, testHook.beforeIdentifyCalls.get(0).get("seriesContext"));
            assertEquals(1, testHook.afterIdentifyCalls.size());
            assertEquals(identifySeriesContext, testHook.afterIdentifyCalls.get(0).get("seriesContext"));
            assertEquals(identifySeriesResult, testHook.afterIdentifyCalls.get(0).get("result"));

            assertEquals(1, addedHook.beforeIdentifyCalls.size());
            assertEquals(identifySeriesContext, addedHook.beforeIdentifyCalls.get(0).get("seriesContext"));
            assertEquals(1, addedHook.afterIdentifyCalls.size());
            assertEquals(identifySeriesContext, addedHook.afterIdentifyCalls.get(0).get("seriesContext"));
            assertEquals(identifySeriesResult, addedHook.afterIdentifyCalls.get(0).get("result"));

            ldClient.trackMetric("test-event", LDValue.buildObject().put("data", "test").build(), 123.45);

            TrackSeriesContext trackSeriesContext = new TrackSeriesContext("test-event", newContext, LDValue.buildObject().put("data", "test").build(), 123.45);

            assertEquals(1, testHook.afterTrackCalls.size());
            assertEquals(trackSeriesContext, testHook.afterTrackCalls.get(0).get("seriesContext"));

            assertEquals(1, addedHook.afterTrackCalls.size());
            assertEquals(trackSeriesContext, addedHook.afterTrackCalls.get(0).get("seriesContext"));

            logging.assertNoWarningsLogged();
            logging.assertNoErrorsLogged();
        }
    }

    private LDConfig makeOfflineConfig() {
        return makeOfflineConfig(null);
    }

    private LDConfig makeOfflineConfig(List<Hook> hooks) {
        LDConfig.Builder builder = new LDConfig.Builder(LDConfig.Builder.AutoEnvAttributes.Disabled)
            .mobileKey(mobileKey)
            .offline(true)
            .events(Components.noEvents())
            .logAdapter(logging.logAdapter);

        if (hooks != null) {
            builder.hooks(Components.hooks().setHooks(hooks));
        }

        return builder.build();
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
