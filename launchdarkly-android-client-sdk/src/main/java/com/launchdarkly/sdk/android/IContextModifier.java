package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.LDContext;

public interface IContextModifier {

    /**
     * Modifies the provided context and returns the resulting context.
     * @param context
     * @return
     */
    public LDContext modifyContext(LDContext context);

}
