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
