package com.launchdarkly.android.flagstore;

import java.util.List;

import javax.annotation.Nullable;

public interface FlagStore {

    void delete();

    void clear();

    boolean containsKey(String key);

    @Nullable
    Flag getFlag(String flagKey);

    void applyFlagUpdate(FlagUpdate flagUpdate);

    void applyFlagUpdates(List<? extends FlagUpdate> flagUpdates);

    void clearAndApplyFlagUpdates(List<? extends FlagUpdate> flagUpdates);

    List<Flag> getAllFlags();

    void registerOnStoreUpdatedListener(StoreUpdatedListener storeUpdatedListener);

    void unregisterOnStoreUpdatedListener();
}
