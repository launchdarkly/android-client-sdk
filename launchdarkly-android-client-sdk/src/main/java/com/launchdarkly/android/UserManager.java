package com.launchdarkly.android;

interface UserManager {
    LDUser getCurrentUser();
    void putCurrentUserFlags(String json, Util.ResultCallback<Void> onCompleteListener);
    void patchCurrentUserFlags(String json, Util.ResultCallback<Void> onCompleteListener);
    void deleteCurrentUserFlag(String json, Util.ResultCallback<Void> onCompleteListener);
    void updateCurrentUser(Util.ResultCallback<Void> onCompleteListener);
}
