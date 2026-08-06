package com.launchdarkly.example;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EvaluationExposureDeduper;
import com.launchdarkly.sdk.android.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.android.integrations.Hook;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the evaluation series stages it observes, so the example can show what exposure
 * deduplication does. The hook declares its own dedupe window, which is how a hook shipped by a
 * plugin would choose its policy.
 * <p>
 * Deduplication skips the whole series, so both counts stay equal and both stop climbing while
 * repeated evaluations resolve to the same result.
 */
class ExposureCountingHook extends Hook {
    private static final int DEDUPE_MAX_SIZE = 2_000;

    private final String label;
    private final int dedupeWindowMillis;
    private final Runnable onStage;
    private final AtomicInteger befores = new AtomicInteger();
    private final AtomicInteger afters = new AtomicInteger();

    /**
     * @param label a name for this hook, shown in the app's dedupe status
     * @param dedupeWindowMillis the hook's dedupe window; zero or negative observes every evaluation
     * @param onStage run after each stage so the app can refresh its display
     */
    ExposureCountingHook(String label, int dedupeWindowMillis, Runnable onStage) {
        super(label);
        this.label = label;
        this.dedupeWindowMillis = dedupeWindowMillis;
        this.onStage = onStage;
        evaluationExposureDeduper(dedupeWindowMillis > 0
                ? new EvaluationExposureDeduper(dedupeWindowMillis, DEDUPE_MAX_SIZE)
                : EvaluationExposureDeduper.disabled());
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
     * @return a line describing this hook's window and how many evaluations have reached it
     */
    String status() {
        return String.format(Locale.US, "%s (%s): %d (before %d / after %d)",
                label,
                dedupeWindowMillis > 0 ? dedupeWindowMillis + " ms" : "no dedupe",
                afters.get(),
                befores.get(),
                afters.get());
    }
}
