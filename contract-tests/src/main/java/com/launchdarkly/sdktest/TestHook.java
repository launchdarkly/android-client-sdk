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

        Map<String, Object> newData = new HashMap<>(data);
        if (hookData.beforeEvaluation != null) {
            newData.putAll(hookData.beforeEvaluation);
        }

        // The rows' own merge law: the callback prices the merged series state
        // (the SDK's own per-hook merge rides BEFORE the post; HOOK 1.2.x).
        EvaluationSeriesCallbackParams params = new EvaluationSeriesCallbackParams();
        params.evaluationSeriesContext = seriesContext;
        params.evaluationSeriesData = newData;
        params.stage = "beforeEvaluation";

        callbackService.post(params);

        return Collections.unmodifiableMap(newData);
    }

    @Override
    public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> data, EvaluationDetail<LDValue> evaluationDetail) {
        if (hookErrors.afterEvaluation != null) {
            throw new RuntimeException(hookErrors.afterEvaluation);
        }

        Map<String, Object> newData = new HashMap<>(data);
        if (hookData.afterEvaluation != null) {
            newData.putAll(hookData.afterEvaluation);
        }

        EvaluationSeriesCallbackParams params = new EvaluationSeriesCallbackParams();
        params.evaluationSeriesContext = seriesContext;
        params.evaluationSeriesData = newData;
        params.evaluationDetail = evaluationDetail;
        params.stage = "afterEvaluation";

        callbackService.post(params);

        return Collections.unmodifiableMap(newData);
    }


    /** The explicit identify flag: the SDK's own hook base also fires the identify
     * series at createClient's init (the initial context); the harness's rows
     * price the explicit-identify face, so the seat buffers the init fires and
     * serves only the face the series rows name. */
    static final java.util.concurrent.atomic.AtomicBoolean IDENTIFY_EXPLICIT =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public Map<String, Object> beforeIdentify(
            com.launchdarkly.sdk.android.integrations.IdentifySeriesContext seriesContext, Map<String, Object> seriesData) {
        if (!IDENTIFY_EXPLICIT.get()) {
            // the init-context face: not priced (the rows' own series state)
            return seriesData;
        }
        Map<String, Object> newData = new HashMap<>(data(seriesData));
        if (hookData.beforeIdentify != null) {
            newData.putAll(hookData.beforeIdentify);
        }

        Representations.IdentifySeriesCallbackParams params = new Representations.IdentifySeriesCallbackParams();
        params.identifySeriesContext = seriesContext;
        params.identifySeriesData = newData;
        params.stage = "beforeIdentify";

        callbackService.post(params);

        return Collections.unmodifiableMap(newData);
    }

    private static Map<String, Object> data(Map<String, Object> seriesData) {
        return seriesData;
    }

    @Override
    public Map<String, Object> afterIdentify(
            com.launchdarkly.sdk.android.integrations.IdentifySeriesContext seriesContext,
            Map<String, Object> seriesData,
            com.launchdarkly.sdk.android.integrations.IdentifySeriesResult result) {
        if (!IDENTIFY_EXPLICIT.get()) {
            // the init face: never posted
            Map<String, Object> newData0 = new HashMap<>(data(seriesData));
            if (hookData.afterIdentify != null) {
                newData0.putAll(hookData.afterIdentify);
            }
            return Collections.unmodifiableMap(newData0);
        }
        Map<String, Object> newData = new HashMap<>(data(seriesData));
        if (hookData.afterIdentify != null) {
            newData.putAll(hookData.afterIdentify);
        }

        Representations.IdentifySeriesCallbackParams params = new Representations.IdentifySeriesCallbackParams();
        params.identifySeriesContext = seriesContext;
        params.identifySeriesData = newData;
        params.identifyResult = result;
        params.stage = "afterIdentify";

        callbackService.post(params);

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
        params.evaluationSeriesData = afterTrackSeriesData();

        callbackService.post(params);
    }

    /** HOOK 1.6.x: afterTrack runs in registration order; each hook's payload
     * prices the union of every registered hook's own afterTrack data up to and
     * including itself, per event (the harness's accumulated series shape). */
    private java.util.LinkedHashMap<String, Object> afterTrackSeriesData() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (TestHook h : REGISTERED) {
            if (h.hookData != null && h.hookData.afterTrack != null) {
                m.putAll(h.hookData.afterTrack);
            }
            if (h == this) {
                break;
            }
        }
        return m;
    }

    /** The client-scoped hook registry (registration order). */
    static final java.util.List<TestHook> REGISTERED = Collections.synchronizedList(new java.util.ArrayList<>());
}