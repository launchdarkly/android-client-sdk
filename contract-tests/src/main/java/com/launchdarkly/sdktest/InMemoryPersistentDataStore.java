package com.launchdarkly.sdktest;

import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * An implementation of {@link PersistentDataStore} that keeps data in a simple in-memory map and
 * does not write it to SharedPreferences.
 * <p>
 * Currently this component is used only in testing, to ensure that tests do not interfere with each
 * other's state. In the future, we may provide a way for applications to choose a persistence
 * implementation.
 */
final class InMemoryPersistentDataStore implements PersistentDataStore {
    private final Map<String, Map<String, String>> data = new HashMap<>();

    @Override
    public synchronized String getValue(String storeNamespace, String key) {
        Map<String, String> namespaceMap = data.get(storeNamespace);
        return namespaceMap == null ? null : namespaceMap.get(key);
    }

    @Override
    public synchronized void setValue(String storeNamespace, String key, String value) {
        Map<String, String> namespaceMap = data.get(storeNamespace);
        if (namespaceMap == null) {
            namespaceMap = new HashMap<>();
            data.put(storeNamespace, namespaceMap);
        }
        namespaceMap.put(key, value);
    }

    @Override
    public synchronized void setValues(String storeNamespace, Map<String, String> keysAndValues) {
        Map<String, String> namespaceMap = data.get(storeNamespace);
        if (namespaceMap == null) {
            namespaceMap = new HashMap<>();
            data.put(storeNamespace, namespaceMap);
        }
        for (Map.Entry<String, String> kv: keysAndValues.entrySet()) {
            if (kv.getValue() == null) {
                namespaceMap.remove(kv.getKey());
            } else {
                namespaceMap.put(kv.getKey(), kv.getValue());
            }
        }
    }

    @Override
    public synchronized Collection<String> getKeys(String storeNamespace) {
        Map<String, String> namespaceMap = data.get(storeNamespace);
        return namespaceMap == null ? Collections.emptyList() : namespaceMap.keySet();
    }

    @Override
    public synchronized void clear(String storeNamespace, boolean fullyDelete) {
        data.remove(storeNamespace);
    }
}
