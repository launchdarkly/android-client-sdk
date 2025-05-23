package com.launchdarkly.sdktest;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.android.integrations.Hook;

import com.launchdarkly.sdk.android.integrations.TrackSeriesContext;
import com.launchdarkly.sdktest.Representations.EvaluationSeriesCallbackParams;
import com.launchdarkly.sdktest.Representations.TrackSeriesCallbackParams;
import com.launchdarkly.sdktest.Representations.HookData;
import com.launchdarkly.sdktest.Representations.HookErrors;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class TestHook extends Hook {
    private final HookCallbackService callbackService;
    private final HookData hookData;
    private final HookErrors hookErrors;

    public TestHook(String name, HookCallbackService callbackService, HookData data, HookErrors errors) {
        super(name);
        this.callbackService = callbackService;
        this.hookData = data;
        this.hookErrors = errors;
    }

    @Override
    public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> data) {
        if (hookErrors.beforeEvaluation != null) {
            throw new RuntimeException(hookErrors.beforeEvaluation);
        }

        EvaluationSeriesCallbackParams params = new EvaluationSeriesCallbackParams();
        params.evaluationSeriesContext = seriesContext;
        params.evaluationSeriesData = data;
        params.stage = "beforeEvaluation";

        callbackService.post(params);

        Map<String, Object> newData = new HashMap<>(data);
        if (hookData.beforeEvaluation != null) {
            newData.putAll(hookData.beforeEvaluation);
        }

        return Collections.unmodifiableMap(newData);
    }

    @Override
    public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> data, EvaluationDetail<LDValue> evaluationDetail) {
        if (hookErrors.afterEvaluation != null) {
            throw new RuntimeException(hookErrors.afterEvaluation);
        }

        EvaluationSeriesCallbackParams params = new EvaluationSeriesCallbackParams();
        params.evaluationSeriesContext = seriesContext;
        params.evaluationSeriesData = data;
        params.evaluationDetail = evaluationDetail;
        params.stage = "afterEvaluation";

        callbackService.post(params);

        Map<String, Object> newData = new HashMap<>();
        if (hookData.afterEvaluation != null) {
            newData.putAll(hookData.afterEvaluation);
        }

        return Collections.unmodifiableMap(newData);
    }

    @Override
    public void afterTrack(TrackSeriesContext seriesContext) {
        if (hookErrors.afterTrack != null) {
            throw new RuntimeException(hookErrors.afterTrack);
        }

        TrackSeriesCallbackParams params = new TrackSeriesCallbackParams();
        params.trackSeriesContext = seriesContext;
        params.stage = "afterTrack";

        callbackService.post(params);
    }
}
