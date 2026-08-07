package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDValue;

import java.util.Map;

/**
 * A Hook is a set of user-defined callbacks that are executed by the SDK at various points of interest. To create
 * your own hook with customized logic, implement the {@link Hook} interface.
 * <p>
 * Multiple hooks may be configured in the SDK. By default, the SDK will execute each hook's before
 * stages in the order they were configured, and each hook's after stages in reverse order. (i.e.
 * myHook1.beforeEvaluation, myHook2.beforeEvaluation, myHook2.afterEvaluation, myHook1.afterEvaluation)
 */
public abstract class Hook {

    private final HookMetadata metadata;

    private EvaluationExposureDeduper evaluationExposureDeduper;

    /**
     * @return the hooks metadata
     */
    public HookMetadata getMetadata() {
        return metadata;
    }

    /**
     * Creates an instance of {@link Hook} with the given name which will be put into its metadata.
     *
     * @param name a friendly name for the hooks
     */
    public Hook(String name) {
        metadata = new HookMetadata(name) {};
    }

    /**
     * Deduplicates this hook's evaluation series with the SDK's implementation, using a window of
     * {@link EvaluationExposureDeduper#DEFAULT_WINDOW_MILLIS}.
     *
     * <pre><code>
     *     Components.hooks()
     *         .addHook(new ObservabilityHook().evaluationExposureDeduper())
     * </code></pre>
     *
     * @return this hook
     * @see #evaluationExposureDeduper(int)
     */
    public Hook evaluationExposureDeduper() {
        return evaluationExposureDeduper(new EvaluationExposureDeduper());
    }

    /**
     * Deduplicates this hook's evaluation series with the SDK's implementation, so that repeated
     * evaluations resolving to the same result reach it at most once per window.
     * <p>
     * This hook observes a flag when its result changes, and at most once per window while the result
     * stays the same. This is useful for reducing the telemetry volume produced by frequent
     * re-evaluations, for example a flag that is read on every redraw of a view.
     *
     * <pre><code>
     *     Components.hooks()
     *         .addHook(new ObservabilityHook().evaluationExposureDeduper(60_000))
     * </code></pre>
     *
     * @param windowMillis the dedupe window in milliseconds; zero or negative reports every
     *                     evaluation
     * @return this hook
     */
    public Hook evaluationExposureDeduper(int windowMillis) {
        return evaluationExposureDeduper(new EvaluationExposureDeduper(windowMillis));
    }

    /**
     * Sets which evaluations reach this hook. It affects only this hook.
     * <p>
     * Pass your own subclass of {@link EvaluationExposureDeduper} to implement a policy other than
     * the SDK's, or {@link EvaluationExposureDeduper#disabled()} to state explicitly that this hook
     * observes every evaluation, which is what it does anyway when no deduper is set.
     *
     * <pre><code>
     *     Components.hooks()
     *         .addHook(new ExperimentHook().evaluationExposureDeduper(myCustomDeduper))
     * </code></pre>
     * <p>
     * Deduplication applies to the whole evaluation series, so a suppressed evaluation invokes
     * neither {@link #beforeEvaluation(EvaluationSeriesContext, Map)} nor
     * {@link #afterEvaluation(EvaluationSeriesContext, Map, EvaluationDetail)}. Analytics events are
     * unaffected: feature, debug, and summary events are still recorded for every evaluation, so the
     * evaluation counts LaunchDarkly reports for your flags do not change. What the hook has
     * observed is cleared by {@link com.launchdarkly.sdk.android.LDClient#identify(com.launchdarkly.sdk.LDContext)},
     * so the first evaluation after an identify always reaches it.
     * <p>
     * The SDK reads this once, when the hook is registered, so call it before passing the hook to
     * the SDK. Give each hook its own deduper unless you intend hooks to share a window: the first
     * hook to observe an evaluation starts the window that suppresses the rest.
     *
     * @param evaluationExposureDeduper the deduper for this hook, or null to observe every
     *                                  evaluation
     * @return this hook
     */
    public Hook evaluationExposureDeduper(EvaluationExposureDeduper evaluationExposureDeduper) {
        this.evaluationExposureDeduper = evaluationExposureDeduper;
        return this;
    }

    /**
     * @return the deduper deciding which evaluations reach this hook, or null if it observes every
     *         evaluation
     */
    public final EvaluationExposureDeduper getEvaluationExposureDeduper() {
        return evaluationExposureDeduper;
    }

    /**
     * {@link #beforeEvaluation(EvaluationSeriesContext, Map)} is executed by the SDK at the start of the evaluation of
     * a feature flag. It will not be executed as part of a call to
     * {@link com.launchdarkly.sdk.android.LDClient#allFlags()}.
     * <p>
     * To provide custom data to the series which will be given back to your {@link Hook} at the next stage of the
     * series, return a map containing the custom data.  You should initialize this map from the {@code seriesData}.
     *
     * <pre>
     * {@code
     * HashMap<String, Object> customData = new HashMap<>(seriesData);
     * customData.put("foo", "bar");
     * return Collections.unmodifiableMap(customData);
     * }
     * </pre>
     *
     * @param seriesContext container of parameters associated with this evaluation
     * @param seriesData    immutable data from the previous stage in evaluation series. {@link #beforeEvaluation(EvaluationSeriesContext, Map)}
     *                      is the first stage in this series, so this will be an immutable empty map.
     * @return a map containing custom data that will be carried through to the next stage of the series
     */
    public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
        // default implementation is no-op
        return seriesData;
    }

    /**
     * {@link #afterEvaluation(EvaluationSeriesContext, Map, EvaluationDetail)} is executed by the SDK at the after the
     * evaluation of a feature flag. It will not be executed as part of a call to
     * {@link com.launchdarkly.sdk.android.LDClient#allFlags()}.
     * <p>
     * This is currently the last stage of the evaluation series in the {@link Hook}, but that may not be the case in
     * the future. To ensure forward compatibility, return the {@code seriesData} unmodified.
     *
     * <pre>
     * {@code
     * String value = (String) seriesData.get("foo");
     * doAThing(value);
     * return seriesData;
     * }
     * </pre>
     *
     * @param seriesContext    container of parameters associated with this evaluation
     * @param seriesData       immutable data from the previous stage in evaluation series. {@link #beforeEvaluation(EvaluationSeriesContext, Map)}
     *                         is the first stage in this series, so this will be an immutable empty map.
     * @param evaluationDetail the result of the evaluation that took place before this hook was invoked
     * @return a map containing custom data that will be carried through to the next stage of the series (if added in the future)
     */
    public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData,
                                               EvaluationDetail<LDValue> evaluationDetail) {
        // default implementation is no-op
        return seriesData;
    }

    /**
     * {@link #beforeIdentify(IdentifySeriesContext, Map)} is called during the execution of the identify process before the operation
     * completes, but after any context modifications are performed.
     * <p>
     * To provide custom data to the series which will be given back to your {@link Hook} at the next stage of the
     * series, return a map containing the custom data.  You should initialize this map from the {@code seriesData}.
     *
     * <pre>
     * {@code
     * HashMap<String, Object> customData = new HashMap<>(seriesData);
     * customData.put("foo", "bar");
     * return Collections.unmodifiableMap(customData);
     * }
     * </pre>
     *
     * @param seriesContext Contains information about the evaluation being performed. This is not
     *  mutable.
     * @param seriesData A record associated with each stage of hook invocations. Each stage is called with
     * the data of the previous stage for a series. The input record should not be modified.
     * @return a map containing custom data that will be carried through to the next stage of the series
     */
    public Map<String, Object> beforeIdentify(IdentifySeriesContext seriesContext, Map<String, Object> seriesData) {
        return seriesData;
    }

    /**
     * {@link #afterIdentify(IdentifySeriesContext, Map, IdentifySeriesResult)} is called during the execution of the identify process,
     * after the operation completes.
     * <p>
     * This is currently the last stage of the identify series in the {@link Hook}, but that may not be the case in
     * the future. To ensure forward compatibility, return the {@code seriesData} unmodified.
     *
     * <pre>
     * {@code
     * String value = (String) seriesData.get("foo");
     * doAThing(value);
     * return seriesData;
     * }
     * </pre>
     *
     * @param seriesContext Contains information about the evaluation being performed. This is not
     *  mutable.
     * @param seriesData A record associated with each stage of hook invocations. Each stage is called with
     * the data of the previous stage for a series. The input record should not be modified.
     * @param result The result of the identify operation.
     * @return a map containing custom data that will be carried through to the next stage of the series (if added in the future)
     */
    public Map<String, Object> afterIdentify(IdentifySeriesContext seriesContext, Map<String, Object> seriesData, IdentifySeriesResult result) {
        return seriesData;
    }

    /**
     * {@link #afterTrack(TrackSeriesContext)} is called during the execution of the track process after the event
     * has been enqueued.
     *
     * @param seriesContext Contains information about the track operation being performed. This is not mutable.
     */
    public void afterTrack(TrackSeriesContext seriesContext) {
        //  default implementation is no-op
    }
}
