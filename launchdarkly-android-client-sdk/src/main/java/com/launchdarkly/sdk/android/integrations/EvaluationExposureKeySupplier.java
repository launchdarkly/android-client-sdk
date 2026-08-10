package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.LDContext;

/**
 * Builds the key identifying the result an evaluation is about to return.
 * <p>
 * The SDK gives one of these to each {@link EvaluationSeriesContext} it builds, so that a hook which
 * needs the identity of an evaluation can ask for it without the SDK computing it for hooks that do
 * not. {@link DedupingHook} is the hook that needs it.
 */
@FunctionalInterface
public interface EvaluationExposureKeySupplier {
    /**
     * @param flagKey the key of the flag being evaluated
     * @param context the context the evaluation is for
     * @return the key identifying the result the evaluation will return
     */
    EvaluationExposureKey exposureKey(String flagKey, LDContext context);
}
