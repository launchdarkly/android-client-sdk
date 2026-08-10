package com.launchdarkly.sdk.android.integrations;

import android.os.SystemClock;

import androidx.annotation.VisibleForTesting;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDValue;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps a hook so that repeated evaluations resolving to the same result do not reach it again within
 * a time window.
 * <p>
 * The wrapped hook is told about a flag when its result changes, and at most once per window while the
 * result stays the same. This is useful for reducing the telemetry volume produced by frequent
 * re-evaluations, for example a flag that is read on every redraw of a view. Deduplication is opt-in:
 * a hook that is registered unwrapped observes every evaluation.
 *
 * <pre><code>
 *     Components.hooks()
 *         .addHook(new MetricsHook())                                  // observes every evaluation
 *         .addHook(new DedupingHook(new ObservabilityHook()))           // default window
 *         .addHook(new DedupingHook(new TelemetryHook(), 60_000))
 *         .addHook(new DedupingHook(new ExperimentHook(), myCustomDeduper))
 * </code></pre>
 * <p>
 * Two evaluations resolve to the same result when they agree on everything
 * {@link EvaluationExposureKey} describes. Pass your own {@link EvaluationExposureDeduper} subclass to
 * decide that differently.
 * <p>
 * A suppressed evaluation reaches neither
 * {@link Hook#beforeEvaluation(EvaluationSeriesContext, Map)} nor
 * {@link Hook#afterEvaluation(EvaluationSeriesContext, Map, EvaluationDetail)}, because hooks pair
 * their stages. The identify and track stages are always forwarded. Analytics events are unaffected:
 * feature, debug, and summary events are still recorded for every evaluation, so the evaluation counts
 * LaunchDarkly reports for your flags do not change.
 * <p>
 * What the wrapped hook has been told about is cleared by
 * {@link com.launchdarkly.sdk.android.LDClient#identify(com.launchdarkly.sdk.LDContext)}, so the first
 * evaluation of each flag after an identify always reaches it.
 * <p>
 * Give each hook its own instance unless you intend hooks to share a window: the first hook to be told
 * about an evaluation starts the window that suppresses the rest.
 */
public final class DedupingHook extends HookDecorator {

    /**
     * Reads the clock a window is measured against. Exists so that tests can control it; the SDK has
     * one implementation.
     */
    interface Clock {
        long elapsedMillis();
    }

    /**
     * Counts from boot rather than from the epoch, so that correcting the device clock cannot stretch
     * a window: were this wall clock time, a correction that moved the clock backwards would leave
     * every recorded time in the future and suppress those flags until real time caught up. It also
     * advances while the device sleeps, unlike {@code System.nanoTime()}, so a window is an interval
     * of real time rather than of awake time.
     */
    private static final Clock ELAPSED_REALTIME = SystemClock::elapsedRealtime;

    // Namespaced because it travels in series data that the wrapped hook may also write to.
    private static final String SUPPRESSED = "com.launchdarkly.sdk.android.DedupingHook.suppressed";

    private final EvaluationExposureDeduper deduper;
    private final Clock clock;

    // Returned in place of the wrapped hook's series data when an evaluation is suppressed, and
    // recognized by identity so that stacked instances each recognize only their own suppressions.
    private final Map<String, Object> suppressedSeriesData =
            Collections.singletonMap(SUPPRESSED, this);

    /**
     * Wraps a hook with a window of {@link EvaluationExposureDeduper#DEFAULT_WINDOW_MILLIS}.
     *
     * @param delegate the hook to wrap
     */
    public DedupingHook(Hook delegate) {
        this(delegate, new EvaluationExposureDeduper());
    }

    /**
     * @param delegate     the hook to wrap
     * @param windowMillis the dedupe window in milliseconds; zero or negative forwards every
     *                     evaluation
     */
    public DedupingHook(Hook delegate, int windowMillis) {
        this(delegate, new EvaluationExposureDeduper(windowMillis));
    }

    /**
     * @param delegate the hook to wrap
     * @param deduper  decides which evaluations reach the wrapped hook
     */
    public DedupingHook(Hook delegate, EvaluationExposureDeduper deduper) {
        this(delegate, deduper, ELAPSED_REALTIME);
    }

    @VisibleForTesting
    DedupingHook(Hook delegate, EvaluationExposureDeduper deduper, Clock clock) {
        super(delegate);
        this.deduper = Objects.requireNonNull(deduper, "a deduping hook must have a deduper");
        this.clock = clock;
    }

    /**
     * Forwards the evaluation unless the wrapped hook has just been told about the same result.
     * <p>
     * The decision is made here, before the evaluation runs, so that a suppressed evaluation reaches
     * neither stage of the wrapped hook. An evaluation whose result the SDK did not describe, which is
     * to say a series context built by something other than the SDK, is always forwarded.
     *
     * @param seriesContext container of parameters associated with this evaluation
     * @param seriesData    immutable data from the previous stage in the evaluation series
     * @return the wrapped hook's series data, or data marking the series as suppressed
     */
    @Override
    public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
        EvaluationExposureKey key = seriesContext.getEvaluationExposureKey();
        if (key != null && !deduper.shouldRecord(key, clock.elapsedMillis())) {
            return suppressedSeriesData;
        }
        return super.beforeEvaluation(seriesContext, seriesData);
    }

    /**
     * Forwards the result unless this instance suppressed the series in its before stage.
     *
     * @param seriesContext    container of parameters associated with this evaluation
     * @param seriesData       the data returned by this hook's before stage
     * @param evaluationDetail the result of the evaluation
     * @return the wrapped hook's series data, unchanged if the series was suppressed
     */
    @Override
    public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData,
                                               EvaluationDetail<LDValue> evaluationDetail) {
        if (seriesData != null && seriesData.get(SUPPRESSED) == this) {
            return seriesData;
        }
        return super.afterEvaluation(seriesContext, seriesData, evaluationDetail);
    }

    /**
     * Forgets which results the wrapped hook has been told about, then forwards the stage.
     * <p>
     * Evaluations observed before an identify describe an earlier point in the application's
     * lifecycle, so they are reported again afterwards. This happens even when the context is
     * unchanged, so that identify is a reliable way for an application to mark a new phase of a
     * session.
     *
     * @param seriesContext container of parameters associated with this identify
     * @param seriesData    immutable data from the previous stage in the identify series
     * @return the wrapped hook's series data
     */
    @Override
    public Map<String, Object> beforeIdentify(IdentifySeriesContext seriesContext, Map<String, Object> seriesData) {
        deduper.reset();
        return super.beforeIdentify(seriesContext, seriesData);
    }
}
