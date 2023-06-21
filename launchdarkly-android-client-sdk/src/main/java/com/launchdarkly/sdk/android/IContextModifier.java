package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.LDContext;

/**
 * Modifies contexts when invoked.
 */
public interface IContextModifier {

    /**
     * Modifies the provided context and returns a resulting context.  May result in no changes at
     * the discretion of the implementation.
     *
     * @param context to be modified
     * @return another context that is the result of modification
     */
    LDContext modifyContext(LDContext context);

}
