package com.launchdarkly.sdk.android.integrations;

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

    /**
     * @param method        the variation method that was used to invoke the evaluation.
     * @param key           the key of the feature flag being evaluated.
     * @param context       the context the evaluation was for.
     * @param defaultValue  the user-provided default value for the evaluation.
     */
    public EvaluationSeriesContext(String method, String key, LDContext context, LDValue defaultValue) {
        this.flagKey = key;
        this.context = context;
        this.defaultValue = defaultValue;
        this.method = method;
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
