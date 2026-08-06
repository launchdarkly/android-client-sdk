package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EvaluationExposureDeduper;
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

    /**
     * Builds the key that the per-hook dedupers use to recognize a repeated evaluation. Consulted
     * once per evaluation, before the series opens, and only when at least one hook can suppress.
     */
    @FunctionalInterface
    public interface ExposureKeySupplier {
        String exposureKey(String flagKey, LDContext context);
    }

    /**
     * Creates the deduper for a hook registered without one. Each hook needs its own instance,
     * because a deduper starts a window as soon as it reports an evaluation.
     */
    @FunctionalInterface
    public interface DefaultDeduperFactory {
        EvaluationExposureDeduper create();
    }

    private static final String UNKNOWN_HOOK_NAME = "unknown hook";

    private final LDLogger logger;
    private final List<Hook> hooks = new ArrayList<>();
    // Parallel to hooks: the deduper deciding which evaluations reach the hook at the same index.
    private final List<EvaluationExposureDeduper> dedupers = new ArrayList<>();
    private final DefaultDeduperFactory defaultDeduperFactory;
    private final ExposureKeySupplier exposureKeySupplier;

    // False while every registered hook wants every evaluation, which is the default. Lets the
    // evaluation path skip building the exposure key, which costs more than the checks it feeds.
    private volatile boolean anyDedupeActive = false;

    public HookRunner(LDLogger logger, List<Hook> initialHooks) {
        this(logger, initialHooks, EvaluationExposureDeduper::disabled, (flagKey, context) -> "");
    }

    public HookRunner(LDLogger logger, List<Hook> initialHooks,
                      DefaultDeduperFactory defaultDeduperFactory,
                      ExposureKeySupplier exposureKeySupplier) {
        this.logger = logger;
        this.defaultDeduperFactory = defaultDeduperFactory;
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

    /**
     * Adds a hook, resolving now which evaluations will reach it: the deduper the hook carries, or
     * one built from the SDK's configured defaults if it carries none.
     *
     * @param hook the hook to add
     */
    public void addHook(Hook hook) {
        EvaluationExposureDeduper declared = hook.getEvaluationExposureDeduper();
        EvaluationExposureDeduper deduper = declared == null ? defaultDeduperFactory.create() : declared;
        if (deduper != EvaluationExposureDeduper.disabled()) {
            anyDedupeActive = true;
        }
        // The deduper goes in first so that an evaluation running concurrently with this never sees
        // a hook whose deduper has not been appended yet.
        dedupers.add(deduper);
        hooks.add(hook);
    }

    /**
     * Clears every hook's record of the evaluations it has already observed, so that the next
     * evaluation of each reaches the hook again. Called when the evaluation context changes.
     */
    public void resetEvaluationExposureDedupers() {
        for (EvaluationExposureDeduper deduper : dedupers) {
            deduper.reset();
        }
    }

    /**
     * Returns the hooks that should observe this evaluation.
     * <p>
     * The decision is made before the series opens rather than after the evaluation completes,
     * because hooks pair their stages: the observability plugin starts a span in
     * {@code beforeEvaluation} and ends it in {@code afterEvaluation}, so suppressing only the after
     * stage would leave that span open until something else closed it.
     */
    private List<Hook> hooksForEvaluation(String flagKey, LDContext context) {
        if (!anyDedupeActive || hooks.isEmpty()) {
            return hooks;
        }

        String exposureKey = exposureKeySupplier.exposureKey(flagKey, context);
        long nowMillis = System.currentTimeMillis();
        List<Hook> reporting = new ArrayList<>(hooks.size());
        for (int i = 0; i < hooks.size(); i++) {
            Hook hook = hooks.get(i);
            if (dedupers.get(i).shouldRecord(exposureKey, nowMillis)) {
                reporting.add(hook);
            } else {
                logger.debug("Deduplicated exposure of flag \"{}\" for hook \"{}\"", flagKey,
                        getHookName(hook));
            }
        }
        return reporting;
    }

    public EvaluationDetail<LDValue> withEvaluation(String method, String key, LDContext context, LDValue defaultValue, EvaluationMethod evalMethod) {
        List<Hook> reportingHooks = hooksForEvaluation(key, context);
        if (reportingHooks.isEmpty()) {
            return evalMethod.evaluate();
        }

        List<Map<String, Object>> seriesDataList = new ArrayList<>(reportingHooks.size());
        EvaluationSeriesContext seriesContext = new EvaluationSeriesContext(method, key, context, defaultValue);
        for (int i = 0; i < reportingHooks.size(); i++) {
            Hook currentHook = reportingHooks.get(i);
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
        for (int i = reportingHooks.size() - 1; i >= 0; i--) {
            Hook currentHook = reportingHooks.get(i);
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
