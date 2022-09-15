package com.launchdarkly.sdk.android;

import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.logging.LDLogger;

public class FlagStoreImplTest extends FlagStoreTest {
    private final InMemoryPersistentDataStore store = new InMemoryPersistentDataStore();

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    public FlagStore createFlagStore(String identifier) {
        return new FlagStoreImpl(store, identifier, LDLogger.none());
    }

    @Test
    public void deletesVersionAndStoresDeletedItemPlaceholder() {
        final Flag key1 = new FlagBuilder("key1").version(12).build();

        final FlagStoreImpl flagStore = new FlagStoreImpl(store, "abc", LDLogger.none());
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));
        flagStore.applyFlagUpdate(new DeleteFlagResponse(key1.getKey(), 13));

        Flag updated = flagStore.getFlag(key1.getKey());
        assertNotNull(updated);
        assertEquals(key1.getKey(), updated.getKey());
        assertEquals(13, updated.getVersion());
        assertTrue(updated.isDeleted());
    }

    @Test
    public void doesNotDeleteIfDeletionVersionIsLessThanOrEqualToExistingVersion() {
        final Flag key1 = new FlagBuilder("key1").version(12).build();

        final FlagStoreImpl flagStore = new FlagStoreImpl(store, "abc", LDLogger.none());
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));
        flagStore.applyFlagUpdate(new DeleteFlagResponse(key1.getKey(), 11));
        flagStore.applyFlagUpdate(new DeleteFlagResponse(key1.getKey(), 12));

        Flag updated = flagStore.getFlag(key1.getKey());
        assertEquals(key1.getKey(), updated.getKey());
        assertEquals(key1.getVersion(), updated.getVersion());
        assertEquals(key1.getValue(), updated.getValue());
        assertFalse(updated.isDeleted());
    }

    @Test
    public void updatesVersions() {
        final Flag key1 = new FlagBuilder("key1").version(12).build();
        final Flag updatedKey1 = new FlagBuilder(key1.getKey()).version(15).build();

        final FlagStoreImpl flagStore = new FlagStoreImpl(store, "abc", LDLogger.none());
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));

        flagStore.applyFlagUpdate(updatedKey1);

        assertEquals(15, flagStore.getFlag(key1.getKey()).getVersion());
    }

    @Test
    public void updatesFlagVersions() {
        final Flag key1 = new FlagBuilder("key1").version(100).flagVersion(12).build();
        final Flag updatedKey1 = new FlagBuilder(key1.getKey()).version(101).flagVersion(15).build();

        final FlagStoreImpl flagStore = new FlagStoreImpl(store, "abc", LDLogger.none());
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));

        flagStore.applyFlagUpdate(updatedKey1);

        assertEquals(Integer.valueOf(15), flagStore.getFlag(key1.getKey()).getFlagVersion());
    }

    @Test
    public void versionForEventsReturnsFlagVersionIfPresentOtherwiseReturnsVersion() {
        final Flag withFlagVersion =
                new FlagBuilder("withFlagVersion").version(12).flagVersion(13).build();
        final Flag withOnlyVersion = new FlagBuilder("withOnlyVersion").version(12).build();

        final FlagStoreImpl flagStore = new FlagStoreImpl(store, "abc", LDLogger.none());
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(withFlagVersion, withOnlyVersion));

        assertEquals(flagStore.getFlag(withFlagVersion.getKey()).getVersionForEvents(), 13, 0);
        assertEquals(flagStore.getFlag(withOnlyVersion.getKey()).getVersionForEvents(), 12, 0);
    }
}