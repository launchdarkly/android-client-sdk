package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public final class NullPersistentDataStore implements PersistentDataStore {
    @Override
    public String getValue(String storeNamespace, String key) {
        return null;
    }

    @Override
    public void setValue(String storeNamespace, String key, String value) {}

    @Override
    public void setValues(String storeNamespace, Map<String, String> keysAndValues) {}

    @Override
    public Collection<String> getKeys(String storeNamespace) {
        return Collections.emptyList();
    }

    @Override
    public Collection<String> getAllNamespaces() {
        return Collections.emptyList();
    }

    @Override
    public void clear(String storeNamespace, boolean fullyDelete) {}
}
