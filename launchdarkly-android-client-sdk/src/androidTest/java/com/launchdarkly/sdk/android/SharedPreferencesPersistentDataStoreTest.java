package com.launchdarkly.sdk.android;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class SharedPreferencesPersistentDataStoreTest {
    private static final String BASE_NAMESPACE = SharedPreferencesPersistentDataStoreTest.class.getName();

    private final Application application;

    public SharedPreferencesPersistentDataStoreTest() {
        this.application = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void namespaceNotFound() {
        String subNamespace = "-namespaceNotFound",
                key = "test-key";
        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        assertNull(store.getValue(BASE_NAMESPACE + subNamespace, key));
    }


    @Test
    public void keyNotFoundInExistingNamespace() {
        String subNamespace = "-keyNotFoundInExistingNamespace",
                key1 = "test-key-1",
                key2 = "test-key-2",
                value1 = "test-value-1";
        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(BASE_NAMESPACE + subNamespace, key1, value1);
        assertNull(store.getValue(BASE_NAMESPACE + subNamespace, key2));
    }

    @Test
    public void valuePersistsAcrossInstances() {
        String subNamespace = "-valuePersistsAcrossInstances",
                key = "test-key",
                value = "test-value";
        PersistentDataStore store1 = new SharedPreferencesPersistentDataStore(application);
        store1.setValue(BASE_NAMESPACE + subNamespace, key, value);
        PersistentDataStore store2 = new SharedPreferencesPersistentDataStore(application);
        assertEquals(value, store2.getValue(BASE_NAMESPACE + subNamespace, key));
    }

    @Test
    public void namespacesAreDistinct() {
        String subNamespace1 = "-namespacesAreDistinct-1",
                subNamespace2 = "-namespacesAreDistinct-2",
                key = "test-key",
                value1 = "test-value-1",
                value2 = "test-value-2";

        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(BASE_NAMESPACE + subNamespace1, key, value1);
        store.setValue(BASE_NAMESPACE + subNamespace2, key, value2);

        assertEquals(value1, store.getValue(BASE_NAMESPACE + subNamespace1, key));
        assertEquals(value2, store.getValue(BASE_NAMESPACE + subNamespace2, key));
    }

    @Test
    public void updateValue() {
        String subNamespace = "-updateValue",
                key = "test-key",
                value1 = "test-value-1", value2 = "test-value-2";

        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(BASE_NAMESPACE + subNamespace, key, value1);
        assertEquals(value1, store.getValue(BASE_NAMESPACE + subNamespace, key));

        store.setValue(BASE_NAMESPACE + subNamespace, key, value2);
        assertEquals(value2, store.getValue(BASE_NAMESPACE + subNamespace, key));
    }

    @Test
    public void updateValues() {
        String subNamespace = "-updateValues",
                key1 = "test-key-1", key2 = "test-key-2", key3 = "test-key-3",
                value1 = "test-value-1",  value2a = "test-value-2a",  value2b = "test-value-2a",
                value3 = "test-value-3";

        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(BASE_NAMESPACE + subNamespace, key1, value1);
        store.setValue(BASE_NAMESPACE + subNamespace, key2, value2a);

        Map<String, String> updates = new HashMap<>();
        updates.put(key2, value2b);
        updates.put(key3, value3);
        store.setValues(BASE_NAMESPACE + subNamespace, updates);

        assertEquals(value1, store.getValue(BASE_NAMESPACE + subNamespace, key1));
        assertEquals(value2b, store.getValue(BASE_NAMESPACE + subNamespace, key2));
        assertEquals(value3, store.getValue(BASE_NAMESPACE + subNamespace, key3));
    }

    @Test
    public void removeValue() {
        String subNamespace = "-removeValue",
                key = "test-key",
                value1 = "test-value-1";
        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(BASE_NAMESPACE + subNamespace, key, value1);
        assertEquals(value1, store.getValue(BASE_NAMESPACE + subNamespace, key));
        store.setValue(BASE_NAMESPACE + subNamespace, key, null);
        assertNull(store.getValue(BASE_NAMESPACE + subNamespace, key));
    }

    @Test
    public void getAllNamespaces() {
        String prefix = BASE_NAMESPACE + "-getAllNamespaces";
        String namespace1 = prefix + "-1", namespace2 = prefix + "-2";
        // Here we'll write directly to SharedPreferences so that we can use the *synchronous*
        // update method, SharedPreferences.Editor.commit(). Otherwise, updates won't be visible
        // immediately in the filesystem.
        application.getSharedPreferences(namespace1, Context.MODE_PRIVATE)
            .edit().putString("a", "a").commit();
        application.getSharedPreferences(namespace2, Context.MODE_PRIVATE)
                .edit().putString("a", "a").commit();
        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        Collection<String> namespaces = store.getAllNamespaces();
        assertThat(String.join("/", namespaces), namespaces, hasItems(namespace1, namespace2));
    }

    @Test
    public void clear() {
        String subNamespace = "-clear",
                key1 = "test-key-1", key2 = "test-key-2",
                value1 = "test-value-1",  value2 = "test-value-2";

        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(BASE_NAMESPACE + subNamespace, key1, value1);
        store.setValue(BASE_NAMESPACE + subNamespace, key2, value2);

        store.clear(BASE_NAMESPACE + subNamespace, true);

        assertNull(store.getValue(BASE_NAMESPACE + subNamespace, key1));
        assertNull(store.getValue(BASE_NAMESPACE + subNamespace, key2));
    }

    @Test
    public void getLongIntegerValue() {
        String subNamespace = "-getLongIntegerValue",
                key = "test-key";
        long value = 12345;

        SharedPreferences prefs = application.getSharedPreferences(BASE_NAMESPACE + subNamespace,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(key, value);
        editor.apply();

        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        assertEquals(String.valueOf(value), store.getValue(BASE_NAMESPACE + subNamespace, key));
    }
}
