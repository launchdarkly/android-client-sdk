package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EvaluationExposureKeySupplier;
import com.launchdarkly.sdk.android.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.android.integrations.Hook;
import com.launchdarkly.sdk.android.integrations.IdentifySeriesContext;
import com.launchdarkly.sdk.android.integrations.IdentifySeriesResult;
import com.launchdarkly.sdk.android.integrations.TrackSeriesContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class HookRunner {
    @FunctionalInterface
    public interface EvaluationMethod {
        EvaluationDetail<LDValue> evaluate();
    }

    @FunctionalInterface
    public interface AfterIdentifyMethod {
        void invoke(IdentifySeriesResult result);
    }

    private static final String UNKNOWN_HOOK_NAME = "unknown hook";

    private final LDLogger logger;
    private final List<Hook> hooks = new ArrayList<>();

    // Handed to every evaluation series context, which resolves it only if a hook asks what the
    // evaluation's result identifies. Dedupe is the only thing that asks, and only a hook that has
    // been wrapped in a DedupingHook dedupes, so an application without one never pays for this.
    private final EvaluationExposureKeySupplier exposureKeySupplier;

    /**
     * Builds a runner whose evaluations describe no result, so a hook that asks what one identifies
     * is told nothing and treats every evaluation as its own.
     */
    public HookRunner(LDLogger logger, List<Hook> initialHooks) {
        this(logger, initialHooks, null);
    }

    public HookRunner(LDLogger logger, List<Hook> initialHooks,
                      EvaluationExposureKeySupplier exposureKeySupplier) {
        this.logger = logger;
        this.exposureKeySupplier = exposureKeySupplier;
        for (Hook hook : initialHooks) {
            addHook(hook);
        }
    }

    private String getHookName(Hook hook) {
        try {
            String name = hook.getMetadata().getName();
            return (name == null || name.isEmpty()) ? UNKNOWN_HOOK_NAME : name;
        } catch (Exception e) {
            logger.error("Exception thrown getting metadata for hook. Unable to get hook name.");
            return UNKNOWN_HOOK_NAME;
        }
    }

    public void addHook(Hook hook) {
        hooks.add(hook);
    }

    public EvaluationDetail<LDValue> withEvaluation(String method, String key, LDContext context, LDValue defaultValue, EvaluationMethod evalMethod) {
        if (hooks.isEmpty()) {
            return evalMethod.evaluate();
        }

        List<Map<String, Object>> seriesDataList = new ArrayList<>(hooks.size());
        EvaluationSeriesContext seriesContext =
                new EvaluationSeriesContext(method, key, context, defaultValue, exposureKeySupplier);
        for (int i = 0; i < hooks.size(); i++) {
            Hook currentHook = hooks.get(i);
            try {
                Map<String, Object> seriesData = currentHook.beforeEvaluation(seriesContext, Collections.unmodifiableMap(Collections.emptyMap()));
                seriesDataList.add(Collections.unmodifiableMap(seriesData));
            } catch (Exception e) {
                seriesDataList.add(Collections.unmodifiableMap(Collections.emptyMap()));
                logger.error("During evaluation of flag \"{}\". Stage \"beforeEvaluation\" of hook \"{}\" reported error: {}", key, getHookName(currentHook), e.toString());
            }
        }

        EvaluationDetail<LDValue> result = evalMethod.evaluate();

        // Invoke hooks in reverse order and give them back the series data they gave us.
        for (int i = hooks.size() - 1; i >= 0; i--) {
            Hook currentHook = hooks.get(i);
            try {
                currentHook.afterEvaluation(seriesContext, seriesDataList.get(i), result);
            } catch (Exception e) {
                logger.error("During evaluation of flag \"{}\". Stage \"afterEvaluation\" of hook \"{}\" reported error: {}", key, getHookName(currentHook), e.toString());
            }
        }

        return result;
    }

    public AfterIdentifyMethod identify(LDContext context, Integer timeout) {
        if (hooks.isEmpty()) {
            return (IdentifySeriesResult result) -> {};
        }

        List<Map<String, Object>> seriesDataList = new ArrayList<>(hooks.size());
        IdentifySeriesContext seriesContext = new IdentifySeriesContext(context, timeout);
        for (int i = 0; i < hooks.size(); i++) {
            Hook currentHook = hooks.get(i);
            try {
                Map<String, Object> seriesData = currentHook.beforeIdentify(seriesContext, Collections.unmodifiableMap(Collections.emptyMap()));
                seriesDataList.add(Collections.unmodifiableMap(seriesData));
            } catch (Exception e) {
                seriesDataList.add(Collections.unmodifiableMap(Collections.emptyMap()));
                logger.error("During identify with context \"{}\". Stage \"beforeIdentify\" of hook \"{}\" reported error: {}", context.getKey(), getHookName(currentHook), e.toString());
            }
        }

        return (IdentifySeriesResult result) -> {
            // Invoke hooks in reverse order and give them back the series data they gave us.
            for (int i = hooks.size() - 1; i >= 0; i--) {
                Hook currentHook = hooks.get(i);
                try {
                    currentHook.afterIdentify(seriesContext, seriesDataList.get(i), result);
                } catch (Exception e) {
                    logger.error("During identify with context \"{}\". Stage \"afterIdentify\" of hook \"{}\" reported error: {}", context.getKey(), getHookName(currentHook), e.toString());
                }
            }
        };
    }

    public void afterTrack(String key, LDContext context, LDValue data, Double metricValue) {
        if (hooks.isEmpty()) {
            return;
        }

        TrackSeriesContext seriesContext = new TrackSeriesContext(key, context, data, metricValue);
        // The track series has only an "after" stage, so hooks run in registration order, as required by
        // the shared SDK contract tests (unlike afterEvaluation/afterIdentify, which run in reverse).
        for (int i = 0; i < hooks.size(); i++) {
            Hook currentHook = hooks.get(i);
            try {
                currentHook.afterTrack(seriesContext);
            } catch (Exception e) {
                logger.error("During tracking of event \"{}\". Stage \"afterTrack\" of hook \"{}\" reported error: {}", key, getHookName(currentHook), e.toString());
            }
        }
    }
}
