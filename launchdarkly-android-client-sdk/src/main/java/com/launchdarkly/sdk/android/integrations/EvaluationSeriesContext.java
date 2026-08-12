package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.Nullable;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel;

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

    // The evaluation's own read of the flag, held rather than the key describing it so that an
    // evaluation reaching only hooks that never ask what its result is builds nothing to describe it.
    private final DataModel.Flag flag;

    /**
     * @param method        the variation method that was used to invoke the evaluation.
     * @param key           the key of the feature flag being evaluated.
     * @param context       the context the evaluation was for.
     * @param defaultValue  the user-provided default value for the evaluation.
     */
    public EvaluationSeriesContext(String method, String key, LDContext context, LDValue defaultValue) {
        this(method, key, context, defaultValue, null, null);
    }

    /**
     * Used by the SDK, which has read the flag the evaluation will return a result from. Application
     * code has no use for this constructor: a context built with the four-argument one has no exposure
     * key, and {@link #getEvaluationExposureKey()} explains what that means for a hook that wanted one.
     * <p>
     * This constructor is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
     * It is experimental.
     *
     * @param method              the variation method that was used to invoke the evaluation.
     * @param key                 the key of the feature flag being evaluated.
     * @param context             the context the evaluation was for.
     * @param defaultValue        the user-provided default value for the evaluation.
     * @param exposureKeySupplier builds the key identifying the result of this evaluation, or null if
     *                            the result is not known
     * @param flag                the evaluation's own read of the flag, or null if it was not found
     */
    public EvaluationSeriesContext(String method, String key, LDContext context, LDValue defaultValue,
                                   @Nullable EvaluationExposureKeySupplier exposureKeySupplier,
                                   @Nullable DataModel.Flag flag) {
        this.flagKey = key;
        this.context = context;
        this.defaultValue = defaultValue;
        this.method = method;
        this.exposureKeySupplier = exposureKeySupplier;
        this.flag = flag;
    }

    /**
     * Returns the key identifying the result this evaluation will return, for a hook that decides what
     * to do with an evaluation by whether it has seen the same result before. {@link DedupingHook} is
     * such a hook.
     * <p>
     * This method is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
     * It is experimental.
     * <p>
     * The key describes the evaluation's own read of the flag, the one its result is derived from, so
     * it is available to {@link Hook#beforeEvaluation(EvaluationSeriesContext, Map)} as well as to the
     * after stage, and every hook that asks is told about the same result. It is built on the ask, so
     * an evaluation whose hooks never ask does not pay for one.
     *
     * @return the key identifying this evaluation's result, or null if this context was not built by
     *         the SDK and so has no result to describe
     */
    @Nullable
    public EvaluationExposureKey getEvaluationExposureKey() {
        return exposureKeySupplier == null ? null : exposureKeySupplier.exposureKey(this, flag);
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
