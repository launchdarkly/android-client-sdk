package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.DataModel.Flag;

import static com.launchdarkly.sdk.android.AssertHelpers.assertDataSetsEqual;
import static com.launchdarkly.sdk.android.AssertHelpers.assertFlagsEqual;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.android.subsystems.ChangeSet;
import com.launchdarkly.sdk.android.subsystems.ChangeSetType;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ContextDataManagerApplyTest extends ContextDataManagerTestBase {

    @Test
    public void applyFullReplacesDataAndPersists() {
        Flag flag1 = new FlagBuilder("flag1").version(1).build();
        Flag flag2 = new FlagBuilder("flag2").version(2).build();
        Map<String, Flag> fullItems = new HashMap<>();
        fullItems.put(flag1.getKey(), flag1);
        fullItems.put(flag2.getKey(), flag2);

        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        ChangeSet changeSet = new ChangeSet(
                ChangeSetType.Full,
                Selector.EMPTY,
                fullItems,
                null,
                true
        );
        manager.apply(CONTEXT, changeSet);

        assertFlagsEqual(flag1, manager.getNonDeletedFlag(flag1.getKey()));
        assertFlagsEqual(flag2, manager.getNonDeletedFlag(flag2.getKey()));
        EnvironmentData expected = EnvironmentData.usingExistingFlagsMap(fullItems);
        assertDataSetsEqual(expected, manager.getAllNonDeleted());
        assertContextIsCached(CONTEXT, expected);
    }

    @Test
    public void applyFullWithShouldPersistFalseUpdatesMemoryOnly() {
        Flag flag1 = new FlagBuilder("flag1").version(1).build();
        EnvironmentData initialData = new DataSetBuilder().add(flag1).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        Flag flag2 = new FlagBuilder("flag2").version(2).build();
        Map<String, Flag> fullItems = new HashMap<>();
        fullItems.put(flag2.getKey(), flag2);
        ChangeSet changeSet = new ChangeSet(
                ChangeSetType.Full,
                Selector.EMPTY,
                fullItems,
                null,
                false
        );
        manager.apply(CONTEXT, changeSet);

        assertNull(manager.getNonDeletedFlag(flag1.getKey()));
        assertFlagsEqual(flag2, manager.getNonDeletedFlag(flag2.getKey()));
        assertDataSetsEqual(EnvironmentData.usingExistingFlagsMap(fullItems), manager.getAllNonDeleted());
        assertContextIsCached(CONTEXT, initialData);
    }

    @Test
    public void applyPartialMergesAndPersists() {
        Flag flag1 = new FlagBuilder("flag1").version(1).build();
        EnvironmentData initialData = new DataSetBuilder().add(flag1).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        Flag flag2 = new FlagBuilder("flag2").version(2).build();
        Flag flag1v2 = new FlagBuilder("flag1").version(2).value(false).build();
        Map<String, Flag> partialItems = new HashMap<>();
        partialItems.put(flag2.getKey(), flag2);
        partialItems.put(flag1v2.getKey(), flag1v2);
        ChangeSet changeSet = new ChangeSet(
                ChangeSetType.Partial,
                Selector.EMPTY,
                partialItems,
                null,
                true
        );
        manager.apply(CONTEXT, changeSet);

        assertFlagsEqual(flag1v2, manager.getNonDeletedFlag(flag1.getKey()));
        assertFlagsEqual(flag2, manager.getNonDeletedFlag(flag2.getKey()));
        EnvironmentData expected = new DataSetBuilder().add(flag1v2).add(flag2).build();
        assertDataSetsEqual(expected, manager.getAllNonDeleted());
        assertContextIsCached(CONTEXT, expected);
    }

    @Test
    public void applyPartialRespectsVersion() {
        Flag flag1 = new FlagBuilder("flag1").version(2).build();
        EnvironmentData initialData = new DataSetBuilder().add(flag1).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        Flag flag1Lower = new FlagBuilder("flag1").version(1).value(false).build();
        ChangeSet changeSet = new ChangeSet(
                ChangeSetType.Partial,
                Selector.EMPTY,
                Collections.singletonMap(flag1Lower.getKey(), flag1Lower),
                null,
                true
        );
        manager.apply(CONTEXT, changeSet);

        assertFlagsEqual(flag1, manager.getNonDeletedFlag(flag1.getKey()));
        assertDataSetsEqual(initialData, manager.getAllNonDeleted());
        assertContextIsCached(CONTEXT, initialData);
    }

    @Test
    public void applyNoneDoesNotChangeFlags() {
        Flag flag1 = new FlagBuilder("flag1").version(1).build();
        EnvironmentData initialData = new DataSetBuilder().add(flag1).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        ChangeSet changeSet = new ChangeSet(
                ChangeSetType.None,
                Selector.EMPTY,
                Collections.emptyMap(),
                null,
                false
        );
        manager.apply(CONTEXT, changeSet);

        assertFlagsEqual(flag1, manager.getNonDeletedFlag(flag1.getKey()));
        assertDataSetsEqual(initialData, manager.getAllNonDeleted());
        assertContextIsCached(CONTEXT, initialData);
    }

    @Test
    public void applyStoresSelectorInMemory() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        assertTrue(manager.getSelector().isEmpty());

        Selector selector = Selector.make(42, "state-42");
        Flag flag = new FlagBuilder("flag1").version(1).build();
        ChangeSet changeSet = new ChangeSet(
                ChangeSetType.Full,
                selector,
                Collections.singletonMap(flag.getKey(), flag),
                null,
                false
        );
        manager.apply(CONTEXT, changeSet);

        assertFalse(manager.getSelector().isEmpty());
        assertEquals(42, manager.getSelector().getVersion());
        assertEquals("state-42", manager.getSelector().getState());
    }

    @Test
    public void applyWithEmptySelectorDoesNotOverwriteStoredSelector() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        Selector first = Selector.make(1, "state1");
        Flag flag = new FlagBuilder("flag1").version(1).build();
        manager.apply(CONTEXT, new ChangeSet(
                ChangeSetType.Full, first, Collections.singletonMap(flag.getKey(), flag), null, false));
        assertEquals(1, manager.getSelector().getVersion());

        manager.apply(CONTEXT, new ChangeSet(
                ChangeSetType.Full, Selector.EMPTY, Collections.singletonMap(flag.getKey(), flag), null, false));
        assertEquals(1, manager.getSelector().getVersion());
    }

    @Test
    public void applyDoesNothingWhenContextMismatch() {
        Flag flag1 = new FlagBuilder("flag1").version(1).build();
        EnvironmentData initialData = new DataSetBuilder().add(flag1).build();
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT);
        manager.initData(CONTEXT, initialData);

        LDContext otherContext = LDContext.create("other-context");
        Map<String, Flag> fullItems = Collections.singletonMap(
                "flag2", new FlagBuilder("flag2").version(1).build());
        ChangeSet changeSet = new ChangeSet(
                ChangeSetType.Full,
                Selector.EMPTY,
                fullItems,
                null,
                true
        );
        manager.apply(otherContext, changeSet);

        assertFlagsEqual(flag1, manager.getNonDeletedFlag(flag1.getKey()));
        assertNull(manager.getNonDeletedFlag("flag2"));
        assertDataSetsEqual(initialData, manager.getAllNonDeleted());
        assertContextIsCached(CONTEXT, initialData);
        assertContextIsNotCached(otherContext);
    }

    @Test
    public void mockSinkImplementsBothDataSourceUpdateSinkAndV2() {
        MockComponents.MockDataSourceUpdateSink mock = new MockComponents.MockDataSourceUpdateSink();
        assertNotNull(DataSourceUpdateSink.class.cast(mock));
        DataSourceUpdateSinkV2 v2 = DataSourceUpdateSinkV2.class.cast(mock);
        assertNotNull(v2);
        v2.apply(CONTEXT, new ChangeSet(
                ChangeSetType.None, Selector.EMPTY, Collections.emptyMap(), null, false));
        assertNotNull(mock.expectApply());
    }
}
