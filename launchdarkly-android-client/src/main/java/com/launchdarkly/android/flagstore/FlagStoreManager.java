package com.launchdarkly.android.flagstore;

import com.launchdarkly.android.FeatureFlagChangeListener;

import java.util.Collection;

public interface FlagStoreManager {

    void switchToUser(String userKey);

    FlagStore getCurrentUserStore();

    void registerListener(String key, FeatureFlagChangeListener listener);

    void unRegisterListener(String key, FeatureFlagChangeListener listener);

    Collection<FeatureFlagChangeListener> getListenersByKey(String key);
}
