package com.launchdarkly.sdk.android;
import com.launchdarkly.sdk.LDContext;

interface ContextManager {
    LDContext getCurrentContext();
    void putCurrentContextFlags(String json, LDUtil.ResultCallback<Void> onCompleteListener);
    void patchCurrentContextFlags(String json, LDUtil.ResultCallback<Void> onCompleteListener);
    void deleteCurrentContextFlag(String json, LDUtil.ResultCallback<Void> onCompleteListener);
    void updateCurrentContext(LDUtil.ResultCallback<Void> onCompleteListener);
}
