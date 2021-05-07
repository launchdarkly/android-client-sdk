package com.launchdarkly.sdk.android;
import com.launchdarkly.sdk.LDUser;

interface UserManager {
    LDUser getCurrentUser();
    void putCurrentUserFlags(String json, LDUtil.ResultCallback<Void> onCompleteListener);
    void patchCurrentUserFlags(String json, LDUtil.ResultCallback<Void> onCompleteListener);
    void deleteCurrentUserFlag(String json, LDUtil.ResultCallback<Void> onCompleteListener);
    void updateCurrentUser(LDUtil.ResultCallback<Void> onCompleteListener);
}
