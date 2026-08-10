package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.Nullable;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import java.util.Map;
import java.util.Objects;

/**
 * Represents parameters associated with a feature flag evaluation.  An instance of this class is provided to some
 * stages of series of a {@link Hook} implementation.  For example, see {@link Hook#beforeEvaluation(EvaluationSeriesContext, Map)}
 */
public class EvaluationSeriesContext {

    /**
     * The variation method that was used to invoke the evaluation.  The stability of this string is not
     * guaranteed and should not be used in conditional logic.
     */
    public final String method;

    /**
     * The key of the feature flag being evaluated.
     */
    public final String flagKey;

    /**
     * The context the evaluation was for.
     */
    public final LDContext context;

    /**
     * The user-provided default value for the evaluation.
     */
    public final LDValue defaultValue;

    private final EvaluationExposureKeySupplier exposureKeySupplier;

    // Resolved on demand and remembered, so that an evaluation costs a flag lookup only when a hook
    // asks what its result identifies, and only once however many hooks ask. Guarded by the instance
    // lock, because a hook may ask from any thread.
    private EvaluationExposureKey exposureKey;

    /**
     * @param method        the variation method that was used to invoke the evaluation.
     * @param key           the key of the feature flag being evaluated.
     * @param context       the context the evaluation was for.
     * @param defaultValue  the user-provided default value for the evaluation.
     */
    public EvaluationSeriesContext(String method, String key, LDContext context, LDValue defaultValue) {
        this(method, key, context, defaultValue, null);
    }

    /**
     * Used by the SDK, which knows the result the evaluation will return. Application code has no use
     * for this constructor: a context built with the four-argument one has no exposure key, and
     * {@link #getEvaluationExposureKey()} explains what that means for a hook that wanted one.
     *
     * @param method              the variation method that was used to invoke the evaluation.
     * @param key                 the key of the feature flag being evaluated.
     * @param context             the context the evaluation was for.
     * @param defaultValue        the user-provided default value for the evaluation.
     * @param exposureKeySupplier resolves the key identifying the result of this evaluation, or null
     *                            if the result is not known
     */
    public EvaluationSeriesContext(String method, String key, LDContext context, LDValue defaultValue,
                                   @Nullable EvaluationExposureKeySupplier exposureKeySupplier) {
        this.flagKey = key;
        this.context = context;
        this.defaultValue = defaultValue;
        this.method = method;
        this.exposureKeySupplier = exposureKeySupplier;
    }

    /**
     * Returns the key identifying the result this evaluation will return, for a hook that decides what
     * to do with an evaluation by whether it has seen the same result before. {@link DedupingHook} is
     * such a hook.
     * <p>
     * The key describes the result as the SDK has it stored, which is what the evaluation is about to
     * return, so it is available to {@link Hook#beforeEvaluation(EvaluationSeriesContext, Map)} as
     * well as to the after stage.
     *
     * @return the key identifying this evaluation's result, or null if this context was not built by
     *         the SDK and so has no result to describe
     */
    @Nullable
    public synchronized EvaluationExposureKey getEvaluationExposureKey() {
        if (exposureKey == null && exposureKeySupplier != null) {
            exposureKey = exposureKeySupplier.exposureKey(flagKey, context);
        }
        return exposureKey;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        EvaluationSeriesContext other = (EvaluationSeriesContext)obj;
        return
            Objects.equals(method, other.method) &&
            Objects.equals(flagKey, other.flagKey) &&
            Objects.equals(context, other.context) &&
            Objects.equals(defaultValue, other.defaultValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, flagKey, context, defaultValue);
    }
}
