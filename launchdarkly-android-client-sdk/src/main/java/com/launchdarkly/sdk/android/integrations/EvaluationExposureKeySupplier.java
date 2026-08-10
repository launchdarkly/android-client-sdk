package com.launchdarkly.sdk.android.integrations;

/**
 * Builds the key identifying the result an evaluation is about to return.
 * <p>
 * The SDK gives one of these to each {@link EvaluationSeriesContext} it builds, so that a hook which
 * needs the identity of an evaluation can ask for it without the SDK computing it for hooks that do
 * not. {@link DedupingHook} is the hook that needs it.
 * <p>
 * The whole evaluation is the parameter, rather than the parts of it a key is built from today, so
 * that a component added to {@link EvaluationExposureKey} later does not change this signature.
 */
@FunctionalInterface
public interface EvaluationExposureKeySupplier {
    /**
     * @param seriesContext the evaluation whose result is to be identified
     * @return the key identifying the result the evaluation will return
     */
    EvaluationExposureKey exposureKey(EvaluationSeriesContext seriesContext);
}
