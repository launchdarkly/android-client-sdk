package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import java.util.HashMap;
import java.util.Map;

public class InMemoryPersistentDataStore implements PersistentDataStore {
    private final Map<String, String> data = new HashMap<>();

    @Override
    public synchronized String getValue(String storeNamespace, String key) {
        return data.get(storeNamespace + ":" + key);
    }

    @Override
    public synchronized void setValue(String storeNamespace, String key, String value) {
        data.put(storeNamespace + ":" + key, value);
    }

    @Override
    public synchronized void setValues(String storeNamespace, Map<String, String> keysAndValues) {
        for (Map.Entry<String, String> kv: keysAndValues.entrySet()) {
            setValue(storeNamespace, kv.getKey(), kv.getValue());
        }
    }

    @Override
    public synchronized void clear(String storeNamespace, boolean fullyDelete) {
        data.remove(storeNamespace);
    }

    public int size() {
        return data.size();
    }
}
