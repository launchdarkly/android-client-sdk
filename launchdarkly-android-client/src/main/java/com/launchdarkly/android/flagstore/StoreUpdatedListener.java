package com.launchdarkly.android.flagstore;

public interface StoreUpdatedListener {
    void onStoreUpdate(String flagKey, FlagStoreUpdateType flagStoreUpdateType);
}
