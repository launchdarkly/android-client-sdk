package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.DataModel;

/**
 * Builds the key identifying the result an evaluation is about to return.
 * <p>
 * The SDK gives one of these to each {@link EvaluationSeriesContext} it builds, so that a hook which
 * needs the identity of an evaluation can ask for it without the SDK building one for hooks that do
 * not. {@link DedupingHook} is the hook that needs it.
 * <p>
 * The whole evaluation is a parameter, rather than the parts of it a key is built from today, so that
 * a component added to {@link EvaluationExposureKey} later does not change this signature. The flag is
 * the second parameter because the evaluation has already read it: reading it again here would let an
 * update landing in between describe a result the evaluation does not return.
 */
@FunctionalInterface
public interface EvaluationExposureKeySupplier {
    /**
     * @param seriesContext the evaluation whose result is to be identified
     * @param flag          the evaluation's own read of the flag, which its result is derived from, or
     *                      null if the flag was not found
     * @return the key identifying the result the evaluation will return
     */
    EvaluationExposureKey exposureKey(EvaluationSeriesContext seriesContext, @Nullable DataModel.Flag flag);
}
