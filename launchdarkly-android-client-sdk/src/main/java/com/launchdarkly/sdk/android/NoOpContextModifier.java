package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.LDContext;

/**
 * Context modifier that does nothing to the context.
 */
public class NoOpContextModifier implements IContextModifier {

    @Override
    public LDContext modifyContext(LDContext context) {
        return context;
    }
}
