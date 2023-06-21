package com.launchdarkly.sdk.android;

import static org.easymock.EasyMock.expect;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;

import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;

public class MigrationTest extends EasyMockSupport {
    private final PersistentDataStore store = new InMemoryPersistentDataStore();

    @Rule public LogCaptureRule logging = new LogCaptureRule();

    @Test
    public void doesNothingIfCurrentSchemaIsAlreadyPresent() {
        PersistentDataStore mockStore = strictMock(PersistentDataStore.class);
        expect(mockStore.getValue(Migration.MIGRATIONS_NAMESPACE, Migration.CURRENT_SCHEMA_ID))
                .andReturn(Migration.CURRENT_SCHEMA_ID);
        // will throw an exception if any other methods are called on the store
        replayAll();

        Migration.migrateWhenNeeded(mockStore, logging.logger);
        verifyAll();
    }

    @Test
    public void deletesAllSdkNamespacesFromOldSchemaAndSetsCurrentSchema() {
        String unrelatedNamespace = "not-from-LaunchDarkly", unrelatedKey = "a", unrelatedValue = "b";
        store.setValue(unrelatedNamespace, unrelatedKey, unrelatedValue);

        for (int i = 0; i < 10; i++) {
            store.setValue(Migration.SHARED_PREFS_BASE_KEY + "data" + i, "a", "b");
            // all of these should be deleted by migration, which by default will discard anything
            // that starts with SHARED_PREFS_BASE_KEY
        }

        Migration.migrateWhenNeeded(store, logging.logger);

        assertThat(store.getAllNamespaces().size(), equalTo(2));
        assertCurrentSchemaIdIsPresent();

        // unrelated namespace shouldn't have been modified
        assertThat(store.getKeys(unrelatedNamespace).size(), equalTo(1));
        assertThat(store.getValue(unrelatedNamespace, unrelatedKey), equalTo(unrelatedValue));
    }

    @Test
    public void migratesGeneratedAnonUserKey() {
        String generatedKey = "key12345";
        store.setValue(Migration.SHARED_PREFS_BASE_KEY + "id", "instanceId", generatedKey);

        Migration.migrateWhenNeeded(store, logging.logger);

        assertThat(store.getAllNamespaces().size(), equalTo(2));
        assertCurrentSchemaIdIsPresent();

        PersistentDataStoreWrapper w = new PersistentDataStoreWrapper(store, logging.logger);
        assertThat(w.getOrGenerateContextKey(ContextKind.DEFAULT), equalTo(generatedKey));
    }

    private void assertCurrentSchemaIdIsPresent() {
        assertThat(store.getAllNamespaces(), hasItems(Migration.MIGRATIONS_NAMESPACE));
        assertThat(store.getValue(Migration.MIGRATIONS_NAMESPACE, Migration.CURRENT_SCHEMA_ID),
                equalTo(Migration.CURRENT_SCHEMA_ID));
    }
}
