package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.DataModel.Flag;

import static com.launchdarkly.sdk.android.AssertHelpers.assertDataSetsEqual;
import static com.launchdarkly.sdk.android.AssertHelpers.assertFlagsEqual;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContextDataManagerFlagDataTest extends ContextDataManagerTestBase {
    @Test
    public void getStoredDataNotFound() {
        assertNull(createDataManager().getStoredData(CONTEXT));
    }

    @Test
    public void initDataUpdatesStoredData() {
        EnvironmentData data = new DataSetBuilder().add(new FlagBuilder("flag1").build()).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, data);
        assertDataSetsEqual(data, createDataManager().getStoredData(CONTEXT));
    }

    @Test
    public void initFromStoredData() {
        EnvironmentData data = new DataSetBuilder().add(new FlagBuilder("flag1").build()).build();
        ContextDataManager manager1 = createDataManager();
        manager1.switchToContext(CONTEXT);
        manager1.initData(CONTEXT, data);

        ContextDataManager manager2 = createDataManager();
        manager2.switchToContext(CONTEXT);
        assertDataSetsEqual(data, manager2.getAllNonDeleted());
    }

    @Test
    public void initFromStoredDataNotFound() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
    }

    @Test
    public void getFlagNotFoundWhenNotInitialized() {
        assertNull(createDataManager().getNonDeletedFlag("flag1"));
    }

    @Test
    public void getFlagNotFoundAfterInitialized() {
        EnvironmentData data = new DataSetBuilder().add(new FlagBuilder("flag1").build()).build();
        ContextDataManager manager = createDataManager();
        manager.initData(CONTEXT, data);

        assertNull(manager.getNonDeletedFlag("flag2"));
    }

    @Test
    public void getKnownFlag() {
        Flag flag = new FlagBuilder("flag1").build();
        EnvironmentData data = new DataSetBuilder().add(flag).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, data);

        assertSame(flag, manager.getNonDeletedFlag(flag.getKey()));
        // Since flags are immutable and we're querying the same in-memory cache that we just
        // initialized, it should be literally the same instance.
    }

    @Test
    public void getDeletedFlagReturnsNull() {
        Flag deletedFlag = Flag.deletedItemPlaceholder("flag1", 1);
        EnvironmentData data = new DataSetBuilder().add(deletedFlag).build();
        ContextDataManager manager = createDataManager();
        manager.initData(CONTEXT, data);

        assertNull(manager.getNonDeletedFlag(deletedFlag.getKey()));
    }

    @Test
    public void getAllWhenNotInitialized() {
        EnvironmentData data = createDataManager().getAllNonDeleted();
        assertNotNull(data);
        assertEquals(0, data.getAll().size());
    }

    @Test
    public void getAllWhenInitializedWithEmptyFlags() {
        ContextDataManager manager = createDataManager();
        manager.initData(CONTEXT, new EnvironmentData());

        EnvironmentData data = createDataManager().getAllNonDeleted();
        assertNotNull(data);
        assertEquals(0, data.getAll().size());
    }

    @Test
    public void getAllReturnsFlags() {
        Flag flag1 = new FlagBuilder("flag1").version(1).build(),
                flag2 = new FlagBuilder("flag2").version(2).build();
        EnvironmentData initialData = new DataSetBuilder().add(flag1).add(flag2).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        EnvironmentData actualData = manager.getAllNonDeleted();
        assertNotNull(actualData);
        assertDataSetsEqual(initialData, actualData);
    }

    @Test
    public void getAllFiltersOutDeletedFlags() {
        Flag flag1 = new FlagBuilder("flag1").version(1).build(),
                deletedFlag = Flag.deletedItemPlaceholder("flag2", 2);
        EnvironmentData initialData = new DataSetBuilder().add(flag1).add(deletedFlag).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        EnvironmentData expectedData = new DataSetBuilder().add(flag1).build();
        assertDataSetsEqual(expectedData, manager.getAllNonDeleted());
    }

    @Test
    public void upsertAddsFlag() {
        Flag flag1 = new FlagBuilder("flag1").version(1).build(),
                flag2 = new FlagBuilder("flag2").version(2).build();
        EnvironmentData initialData = new DataSetBuilder().add(flag1).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        manager.upsert(CONTEXT, flag2);

        assertFlagsEqual(flag2, manager.getNonDeletedFlag(flag2.getKey()));

        EnvironmentData expectedData = new DataSetBuilder().add(flag1).add(flag2).build();
        assertDataSetsEqual(expectedData, manager.getAllNonDeleted());
        assertDataSetsEqual(expectedData, manager.getStoredData(CONTEXT));
    }

    @Test
    public void upsertUpdatesFlag() {
        Flag flag1a = new FlagBuilder("flag1").version(1).value(true).build(),
                flag1b = new FlagBuilder(flag1a.getKey()).version(2).value(false).build();
        EnvironmentData initialData = new DataSetBuilder().add(flag1a).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        manager.upsert(CONTEXT, flag1b);

        assertFlagsEqual(flag1b, manager.getNonDeletedFlag(flag1a.getKey()));

        EnvironmentData expectedData = new DataSetBuilder().add(flag1b).build();
        assertDataSetsEqual(expectedData, manager.getAllNonDeleted());
        assertDataSetsEqual(expectedData, manager.getStoredData(CONTEXT));
    }

    @Test
    public void upsertDoesNotUpdateFlagWithSameVersion() {
        upsertDoesNotUpdateFlag(
                new FlagBuilder("flag1").version(1).value(true).build(),
                new FlagBuilder("flag1").version(1).value(false).build()
        );
    }

    @Test
    public void upsertDoesNotUpdateFlagWithLowerVersion() {
        upsertDoesNotUpdateFlag(
                new FlagBuilder("flag1").version(2).value(true).build(),
                new FlagBuilder("flag1").version(1).value(false).build()
        );
    }

    @Test
    public void upsertDeletesFlag() {
        Flag flag1 = new FlagBuilder("flag1").version(1).value(true).build(),
                flag2 = new FlagBuilder("flag2").version(1).value(true).build(),
                deletedFlag2 = Flag.deletedItemPlaceholder(flag2.getKey(), 2);
        EnvironmentData initialData = new DataSetBuilder().add(flag1).add(flag2).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        manager.upsert(CONTEXT, deletedFlag2);

        assertFlagsEqual(flag1, manager.getNonDeletedFlag(flag1.getKey()));
        assertNull(manager.getNonDeletedFlag(flag2.getKey()));

        EnvironmentData expectedAllData = new DataSetBuilder().add(flag1).add(deletedFlag2).build();
        EnvironmentData expectedNonDeleted = new DataSetBuilder().add(flag1).build();
        assertDataSetsEqual(expectedAllData, manager.getStoredData(CONTEXT));
        assertDataSetsEqual(expectedNonDeleted, manager.getAllNonDeleted());
    }

    @Test
    public void upsertDoesNotDeleteFlagWithSameVersion() {
        upsertDoesNotUpdateFlag(
                new FlagBuilder("flag1").version(1).value(true).build(),
                Flag.deletedItemPlaceholder("flag1", 1)
        );
    }

    @Test
    public void upsertDoesNotDeleteFlagWithLowerVersion() {
        upsertDoesNotUpdateFlag(
                new FlagBuilder("flag1").version(2).value(true).build(),
                Flag.deletedItemPlaceholder("flag1", 1)
        );
    }

    private void upsertDoesNotUpdateFlag(Flag initialFlag, Flag updatedFlag) {
        EnvironmentData initialData = new DataSetBuilder().add(initialFlag).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        manager.upsert(CONTEXT, updatedFlag);

        assertFlagsEqual(initialFlag, manager.getNonDeletedFlag(initialFlag.getKey()));
        assertDataSetsEqual(initialData, manager.getAllNonDeleted());
        assertDataSetsEqual(initialData, manager.getStoredData(CONTEXT));
    }
}
