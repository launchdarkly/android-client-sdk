package com.launchdarkly.sdk.android;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        SharedPreferencesPersistentDataStore store1 = new SharedPreferencesPersistentDataStore(application);
        store1.setValue(BASE_NAMESPACE + subNamespace, key, value);
        // Writes coalesce and flush asynchronously; drain before dropping the reference so
        // that a second, independent store can observe the value via disk.
        store1.flushSynchronously();
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

    @Test
    public void getKeysReturnsSetKeys() {
        String subNamespace = "-getKeysReturnsSetKeys",
                key1 = "test-key-1", key2 = "test-key-2",
                value1 = "test-value-1", value2 = "test-value-2";
        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(BASE_NAMESPACE + subNamespace, key1, value1);
        store.setValue(BASE_NAMESPACE + subNamespace, key2, value2);

        Collection<String> keys = store.getKeys(BASE_NAMESPACE + subNamespace);
        assertThat(keys, hasItems(key1, key2));
        assertEquals(2, keys.size());
    }

    @Test
    public void getKeysExcludesRemovedKey() {
        String subNamespace = "-getKeysExcludesRemovedKey",
                key1 = "test-key-1", key2 = "test-key-2",
                value1 = "test-value-1", value2 = "test-value-2";
        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(BASE_NAMESPACE + subNamespace, key1, value1);
        store.setValue(BASE_NAMESPACE + subNamespace, key2, value2);
        // Force the two writes out to disk so the subsequent null-write sits above the
        // disk keyset via the pending overlay, rather than being coalesced into the
        // same State as the two puts.
        store.flushSynchronously();
        // setValue with null value removes the key.
        store.setValue(BASE_NAMESPACE + subNamespace, key1, null);

        Collection<String> keys = store.getKeys(BASE_NAMESPACE + subNamespace);
        assertThat(keys, hasItems(key2));
        assertEquals(1, keys.size());
    }

    @Test
    public void getKeysAfterClearIsEmpty() {
        String subNamespace = "-getKeysAfterClearIsEmpty",
                key1 = "test-key-1", key2 = "test-key-2",
                value1 = "test-value-1", value2 = "test-value-2";
        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(BASE_NAMESPACE + subNamespace, key1, value1);
        store.setValue(BASE_NAMESPACE + subNamespace, key2, value2);
        // Force the two writes out to disk so the clear operates against a real disk
        // keyset via the pending overlay, not merged into the same pending State.
        store.flushSynchronously();
        store.clear(BASE_NAMESPACE + subNamespace, false);

        assertEquals(0, store.getKeys(BASE_NAMESPACE + subNamespace).size());
    }

    @Test
    public void getKeysReflectsMixedDiskAndPendingActivity() {
        String subNamespace = "-getKeysReflectsMixedDiskAndPendingActivity",
                keyDisk = "disk-key", keyPending = "pending-key", keyRemoved = "removed-key",
                valueDisk = "disk-value", valuePending = "pending-value", valueRemoved = "removed-value";
        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        // Two keys on disk: one stays visible, one gets removed via a pending null.
        store.setValue(BASE_NAMESPACE + subNamespace, keyDisk, valueDisk);
        store.setValue(BASE_NAMESPACE + subNamespace, keyRemoved, valueRemoved);
        store.flushSynchronously();

        // Add a new key via pending, and remove one of the disk keys via a pending null.
        store.setValue(BASE_NAMESPACE + subNamespace, keyPending, valuePending);
        store.setValue(BASE_NAMESPACE + subNamespace, keyRemoved, null);

        Collection<String> keys = store.getKeys(BASE_NAMESPACE + subNamespace);
        assertThat(keys, hasItems(keyDisk, keyPending));
        // keyRemoved must not appear even though it lives on disk — pending's null
        // overlays disk state.
        assertEquals(2, keys.size());
    }

    @Test
    public void clearWithFullyDeleteFalseKeepsFile() {
        String subNamespace = "-clearWithFullyDeleteFalseKeepsFile",
                key = "test-key", value = "test-value";
        String namespace = BASE_NAMESPACE + subNamespace;

        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(namespace, key, value);
        store.flushSynchronously();

        store.clear(namespace, false);
        store.flushSynchronously();

        File file = new File(application.getFilesDir().getParent() + "/shared_prefs/"
                + namespace + ".xml");
        assertTrue("XML file should remain after clear with fullyDelete=false", file.exists());
        assertNull(store.getValue(namespace, key));
    }

    @Test
    public void clearWithFullyDeleteTrueRemovesFile() {
        String subNamespace = "-clearWithFullyDeleteTrueRemovesFile",
                key = "test-key", value = "test-value";
        String namespace = BASE_NAMESPACE + subNamespace;

        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(namespace, key, value);
        store.flushSynchronously();

        store.clear(namespace, true);
        store.flushSynchronously();

        File file = new File(application.getFilesDir().getParent() + "/shared_prefs/"
                + namespace + ".xml");
        assertFalse("XML file should be removed after clear with fullyDelete=true", file.exists());
        assertNull(store.getValue(namespace, key));
    }

    @Test
    public void clearThenSetValueForSameKeyRetainsNewValue() {
        String subNamespace = "-clearThenSetValueForSameKeyRetainsNewValue";
        String namespace = BASE_NAMESPACE + subNamespace;
        String key = "shared-key";

        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(namespace, key, "disk-value");
        store.flushSynchronously();

        // One coalesced State: clear then setValue for the SAME key. The commit must
        // produce a namespace containing only the post-clear value.
        store.clear(namespace, true);
        store.setValue(namespace, key, "post-clear-value");
        store.flushSynchronously();

        assertEquals("post-clear-value", store.getValue(namespace, key));

        // Second store confirms the same state on disk.
        PersistentDataStore store2 = new SharedPreferencesPersistentDataStore(application);
        assertEquals("post-clear-value", store2.getValue(namespace, key));
    }

    @Test
    public void getAllNamespacesIncludesPendingWrites() {
        String subNamespace = "-getAllNamespacesIncludesPendingWrites";
        String namespace = BASE_NAMESPACE + subNamespace;
        PersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        // Write via our store and check getAllNamespaces without flushing. The union of
        // filesystem + pending + committing must include the namespace regardless of
        // whether the drain has completed yet.
        store.setValue(namespace, "k", "v");
        assertThat(store.getAllNamespaces(), hasItems(namespace));
    }

    @Test
    public void getValueReadsPendingWriteBeforeDisk() {
        String subNamespace = "-getValueReadsPendingWriteBeforeDisk";
        String namespace = BASE_NAMESPACE + subNamespace;
        String key = "test-key";

        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        store.setValue(namespace, key, "disk-value");
        store.flushSynchronously();

        // Overwrite; before flushing this value only exists in the in-memory layer.
        store.setValue(namespace, key, "pending-value");
        assertEquals("pending-value", store.getValue(namespace, key));
    }

    @Test(timeout = 5000)
    public void flushSynchronouslyOnEmptyStore() {
        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        // No work queued: should return promptly rather than hang.
        store.flushSynchronously();
    }

    @Test
    public void setValuesWithEmptyMap() {
        String subNamespace = "-setValuesWithEmptyMap";
        String namespace = BASE_NAMESPACE + subNamespace;
        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);
        // Empty-map input should be a no-op that doesn't crash or hang.
        store.setValues(namespace, new HashMap<>());
        store.flushSynchronously();
    }

    @Test
    public void concurrentWritesCoalesce() throws InterruptedException {
        String subNamespace = "-concurrentWritesCoalesce";
        String namespace = BASE_NAMESPACE + subNamespace;
        int threadCount = 8;
        int writesPerThread = 100;

        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        store.setValue(namespace,
                                "key-" + threadId + "-" + i,
                                "value-" + threadId + "-" + i);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue("threads did not complete in time",
                doneLatch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        store.flushSynchronously();

        // A fresh store reads only from disk; every key/value must be present.
        PersistentDataStore store2 = new SharedPreferencesPersistentDataStore(application);
        for (int t = 0; t < threadCount; t++) {
            for (int i = 0; i < writesPerThread; i++) {
                String key = "key-" + t + "-" + i;
                String expected = "value-" + t + "-" + i;
                assertEquals("key " + key + " missing on disk", expected,
                        store2.getValue(namespace, key));
            }
        }
    }

    @Test(timeout = 5000)
    public void getValueUsesCorrectLayerPrecedenceForSameKey() throws Exception {
        String subNamespace = "-getValueUsesCorrectLayerPrecedenceForSameKey";
        String namespace = BASE_NAMESPACE + subNamespace;
        String key = "shared-key";

        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);

        // Layer 3 (disk) holds "disk-value".
        store.setValue(namespace, key, "disk-value");
        store.flushSynchronously();

        CountDownLatch atBarrier = new CountDownLatch(1);
        CountDownLatch releaseBarrier = new CountDownLatch(1);
        store.preCommitRunnable = () -> {
            atBarrier.countDown();
            try {
                releaseBarrier.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            // Trigger a commit that puts "committing-value" into committing for the same key.
            store.setValue(namespace, key, "committing-value");
            assertTrue("drain thread did not reach preCommitRunnable",
                    atBarrier.await(5, TimeUnit.SECONDS));

            // Layers: pending empty, committing has key→"committing-value",
            // disk has key→"disk-value". Committing must win over disk.
            assertEquals("committing-value", store.getValue(namespace, key));

            // Add "pending-value" for the same key into pending.
            store.setValue(namespace, key, "pending-value");

            // Layers: pending has key→"pending-value", committing has key→"committing-value",
            // disk has key→"disk-value". Pending must win over committing and disk.
            assertEquals("pending-value", store.getValue(namespace, key));

            // A null in pending is a pending-layer removal; getValue must return null
            // even though committing and disk still hold non-null values.
            store.setValue(namespace, key, null);
            assertNull(store.getValue(namespace, key));
        } finally {
            store.preCommitRunnable = null;
            releaseBarrier.countDown();
            store.flushSynchronously();
        }
    }

    @Test(timeout = 5000)
    public void pendingClearShadowsCommittingAndDiskForSameKey() throws Exception {
        // Same-key variant of the precedence test: verify that pending's clear=true
        // short-circuits lookups even when committing and disk still hold values for
        // the key.
        String subNamespace = "-pendingClearShadowsCommittingAndDiskForSameKey";
        String namespace = BASE_NAMESPACE + subNamespace;
        String key = "shared-key";

        SharedPreferencesPersistentDataStore store = new SharedPreferencesPersistentDataStore(application);

        store.setValue(namespace, key, "disk-value");
        store.flushSynchronously();

        CountDownLatch atBarrier = new CountDownLatch(1);
        CountDownLatch releaseBarrier = new CountDownLatch(1);
        store.preCommitRunnable = () -> {
            atBarrier.countDown();
            try {
                releaseBarrier.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            store.setValue(namespace, key, "committing-value");
            assertTrue("drain thread did not reach preCommitRunnable",
                    atBarrier.await(5, TimeUnit.SECONDS));

            // Sanity: with only committing and disk populated, committing wins.
            assertEquals("committing-value", store.getValue(namespace, key));

            // Install a pending clear. Pending now has clear=true with no changes for key.
            store.clear(namespace, false);

            // Committing still has key→"committing-value" and disk still has key→"disk-value",
            // but pending's clear must shadow both.
            assertNull(store.getValue(namespace, key));
        } finally {
            store.preCommitRunnable = null;
            releaseBarrier.countDown();
            store.flushSynchronously();
        }

        // After drain: committing state committed (disk gets "committing-value"),
        // then pending's clear state committed (empties the namespace on disk).
        PersistentDataStore store2 = new SharedPreferencesPersistentDataStore(application);
        assertNull(store2.getValue(namespace, key));
    }
}
