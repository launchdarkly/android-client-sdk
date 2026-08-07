package com.launchdarkly.example;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.android.integrations.Hook;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the evaluation series stages it observes, so the example can show what exposure
 * deduplication does. Deduplication is configured at registration with
 * {@link Hook#evaluationExposureDeduper(int)}, the same way a customer would configure any
 * other hook.
 * <p>
 * Deduplication skips the whole series, so both counts stay equal and both stop climbing while
 * repeated evaluations resolve to the same result.
 */
class ExposureCountingHook extends Hook {
    private final String label;
    private final Runnable onStage;
    private final AtomicInteger befores = new AtomicInteger();
    private final AtomicInteger afters = new AtomicInteger();

    /**
     * @param label a name for this hook, shown in the app's dedupe status
     * @param onStage run after each stage so the app can refresh its display
     */
    ExposureCountingHook(String label, Runnable onStage) {
        super(label);
        this.label = label;
        this.onStage = onStage;
    }

    @Override
    public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
        befores.incrementAndGet();
        onStage.run();
        return seriesData;
    }

    @Override
    public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData,
                                               EvaluationDetail<LDValue> evaluationDetail) {
        afters.incrementAndGet();
        onStage.run();
        return seriesData;
    }

    /**
     * @param windowMillis the dedupe window this hook was registered with, for the status line
     * @return a line describing this hook's window and how many evaluations have reached it
     */
    String status(int windowMillis) {
        return String.format(Locale.US, "%s (%d ms): %d (before %d / after %d)",
                label,
                windowMillis,
                afters.get(),
                befores.get(),
                afters.get());
    }
}
