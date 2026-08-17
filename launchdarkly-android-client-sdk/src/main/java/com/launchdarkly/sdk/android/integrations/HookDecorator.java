package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.CallSuper;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDValue;

import java.util.Map;
import java.util.Objects;

/**
 * A hook that wraps another hook, forwarding every stage to it. A subclass adds behavior to a hook
 * without changing it, and is registered in place of the hook it wraps.
 * <p>
 * Every stage forwards to the wrapped hook, so a subclass overrides only the stages it changes, and
 * calls {@code super} to forward the ones it does. The stages it leaves alone still reach the wrapped
 * hook.
 * <p>
 * An override that never calls {@code super} stops forwarding that stage, which for the identify and
 * track stages is a decorator swallowing something it has no reason to: a {@link DedupingHook} inside
 * such an override would stop being told to forget what it has reported, and would go on suppressing
 * across an identify. Those three stages are {@link CallSuper}, so Android Lint reports an override of
 * one that does not forward. The evaluation stages are not, because suppressing an evaluation series is
 * what a decorator is for.
 *
 * <pre><code>
 *     public final class FlagFilteringHook extends HookDecorator {
 *         private final Set&lt;String&gt; flagKeys;
 *
 *         public FlagFilteringHook(Hook delegate, Set&lt;String&gt; flagKeys) {
 *             super(delegate);
 *             this.flagKeys = flagKeys;
 *         }
 *
 *         &#64;Override
 *         public Map&lt;String, Object&gt; beforeEvaluation(EvaluationSeriesContext seriesContext,
 *                                                     Map&lt;String, Object&gt; seriesData) {
 *             return flagKeys.contains(seriesContext.flagKey)
 *                     ? super.beforeEvaluation(seriesContext, seriesData)
 *                     : seriesData;
 *         }
 *     }
 * </code></pre>
 * <p>
 * That hook filters evaluations and still forwards identify and track, which it never mentions.
 * <p>
 * {@link DedupingHook} is the decorator the SDK ships: it forwards an evaluation series only when the
 * flag's result is one its hook has not just been told about.
 * <p>
 * Decorators stack, so a hook may be wrapped in as many as it needs, each wrapping the one inside it:
 *
 * <pre><code>
 *     Components.hooks()
 *         .addHook(new DedupingHook(new FlagFilteringHook(new ObservabilityHook(), myFlagKeys)))
 * </code></pre>
 * <p>
 * A decorator reports the wrapped hook's metadata as its own, so the SDK names the hook that a stage
 * belongs to rather than the wrappers around it.
 * <p>
 * A decorator that suppresses a stage must suppress the whole evaluation series, because hooks pair
 * their stages: an observability hook opens a span in
 * {@link Hook#beforeEvaluation(EvaluationSeriesContext, Map)} and closes it in
 * {@link Hook#afterEvaluation(EvaluationSeriesContext, Map, EvaluationDetail)}, so suppressing only
 * the after stage leaves that span open. To carry the decision from one stage to the other, return
 * series data the after stage recognizes, the way {@link DedupingHook} does.
 * <p>
 * A decorator that does that belongs outermost, because the series data it returns replaces what it
 * was given: a decorator outside it does not get back what it stored in its own before stage.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is experimental.
 */
public abstract class HookDecorator extends Hook {

    // Reachable only through super calls. Handing the wrapped hook back out invites code to unwrap a
    // decorator and decide what to do with what it finds, when a decorator is meant to stand in for the
    // hook it wraps.
    private final Hook delegate;

    /**
     * Wraps a hook, taking its name as this decorator's own.
     *
     * @param delegate the hook to forward each stage to
     */
    protected HookDecorator(Hook delegate) {
        super(nameOf(delegate));
        this.delegate = Objects.requireNonNull(delegate, "a decorator must wrap a hook");
    }

    /**
     * @return the wrapped hook's metadata, so that the SDK names the hook a stage belongs to
     */
    @Override
    public HookMetadata getMetadata() {
        return delegate.getMetadata();
    }

    /**
     * Forwards the stage to the wrapped hook.
     *
     * @param seriesContext container of parameters associated with this evaluation
     * @param seriesData    immutable data from the previous stage in the evaluation series
     * @return the wrapped hook's series data
     */
    @Override
    public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
        return delegate.beforeEvaluation(seriesContext, seriesData);
    }

    /**
     * Forwards the stage to the wrapped hook.
     *
     * @param seriesContext    container of parameters associated with this evaluation
     * @param seriesData       data from the previous stage in the evaluation series
     * @param evaluationDetail the result of the evaluation
     * @return the wrapped hook's series data
     */
    @Override
    public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData,
                                               EvaluationDetail<LDValue> evaluationDetail) {
        return delegate.afterEvaluation(seriesContext, seriesData, evaluationDetail);
    }

    /**
     * Forwards the stage to the wrapped hook.
     *
     * @param seriesContext container of parameters associated with this identify
     * @param seriesData    immutable data from the previous stage in the identify series
     * @return the wrapped hook's series data
     */
    @CallSuper
    @Override
    public Map<String, Object> beforeIdentify(IdentifySeriesContext seriesContext, Map<String, Object> seriesData) {
        return delegate.beforeIdentify(seriesContext, seriesData);
    }

    /**
     * Forwards the stage to the wrapped hook.
     *
     * @param seriesContext container of parameters associated with this identify
     * @param seriesData    data from the previous stage in the identify series
     * @param result        the result of the identify operation
     * @return the wrapped hook's series data
     */
    @CallSuper
    @Override
    public Map<String, Object> afterIdentify(IdentifySeriesContext seriesContext, Map<String, Object> seriesData,
                                             IdentifySeriesResult result) {
        return delegate.afterIdentify(seriesContext, seriesData, result);
    }

    /**
     * Forwards the stage to the wrapped hook.
     *
     * @param seriesContext container of parameters associated with this track
     */
    @CallSuper
    @Override
    public void afterTrack(TrackSeriesContext seriesContext) {
        delegate.afterTrack(seriesContext);
    }

    // Static because it runs in the super() call, before this instance exists. Tolerates a hook whose
    // metadata throws, which the SDK reports rather than propagates.
    private static String nameOf(Hook delegate) {
        try {
            return delegate == null ? null : delegate.getMetadata().getName();
        } catch (Exception e) {
            return null;
        }
    }
}
