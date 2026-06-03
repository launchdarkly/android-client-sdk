package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.integrations.CacheInitializerEntry;
import com.launchdarkly.sdk.android.integrations.InitializerEntry;
import com.launchdarkly.sdk.android.integrations.PollingSynchronizerEntry;
import com.launchdarkly.sdk.android.integrations.StreamingSynchronizerEntry;
import com.launchdarkly.sdk.android.integrations.SynchronizerEntry;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuildInputs;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.InitializerFromCache;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.fdv2.SourceResultType;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class FDv2EntryConverterTest {

    private static final LDContext CONTEXT = LDContext.create("test-user");
    private static final File CACHE_DIR = new File(System.getProperty("java.io.tmpdir"));

    private static DataSourceBuildInputs plainInputs() {
        return new DataSourceBuildInputs(
                CONTEXT, null, null, false,
                () -> Selector.EMPTY, null, CACHE_DIR,
                LDLogger.none());
    }

    @Test
    public void cacheInitializer_isInitializerFromCache() {
        DataSourceBuilder<Initializer> builder =
                FDv2EntryConverter.toInitializerBuilder(new CacheInitializerEntry());
        assertTrue(builder instanceof InitializerFromCache);
    }

    @Test
    public void pollingInitializer_isNotInitializerFromCache() {
        DataSourceBuilder<Initializer> builder =
                FDv2EntryConverter.toInitializerBuilder(DataSystemComponents.pollingInitializer());
        assertFalse(builder instanceof InitializerFromCache);
    }

    @Test
    public void cacheInitializer_buildWithPlainInputs_emptyChangeSet() throws Exception {
        Initializer initializer =
                FDv2EntryConverter.toInitializerBuilder(new CacheInitializerEntry()).build(plainInputs());
        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);
        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertEquals(ChangeSetType.None, result.getChangeSet().getType());
    }

    @Test
    public void unsupportedInitializer_throwsIllegalArgumentException() {
        InitializerEntry unsupported = new InitializerEntry() {};
        try {
            FDv2EntryConverter.toInitializerBuilder(unsupported);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Unsupported InitializerEntry"));
            assertTrue(e.getMessage().contains(unsupported.getClass().getName()));
        }
    }

    @Test
    public void pollingSynchronizer_converts() {
        DataSourceBuilder<Synchronizer> builder = FDv2EntryConverter.toSynchronizerBuilder(
                DataSystemComponents.pollingSynchronizer().pollIntervalMillis(60_000));
        assertNotNull(builder);
    }

    @Test
    public void streamingSynchronizer_converts() {
        DataSourceBuilder<Synchronizer> builder = FDv2EntryConverter.toSynchronizerBuilder(
                DataSystemComponents.streamingSynchronizer().initialReconnectDelayMillis(2_000));
        assertNotNull(builder);
    }

    @Test
    public void unsupportedSynchronizer_throwsIllegalArgumentException() {
        SynchronizerEntry unsupported = new SynchronizerEntry() {};
        try {
            FDv2EntryConverter.toSynchronizerBuilder(unsupported);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Unsupported SynchronizerEntry"));
            assertTrue(e.getMessage().contains(unsupported.getClass().getName()));
        }
    }

    @Test
    public void toInitializerBuilders_preservesOrderAndKinds() {
        List<InitializerEntry> entries = Arrays.asList(
                new CacheInitializerEntry(),
                DataSystemComponents.pollingInitializer());
        List<DataSourceBuilder<Initializer>> builders = FDv2EntryConverter.toInitializerBuilders(entries);
        assertEquals(2, builders.size());
        assertTrue(builders.get(0) instanceof InitializerFromCache);
        assertFalse(builders.get(1) instanceof InitializerFromCache);
    }

    @Test
    public void toInitializerBuilders_emptyList() {
        assertTrue(FDv2EntryConverter.toInitializerBuilders(Collections.emptyList()).isEmpty());
    }

    @Test
    public void toSynchronizerBuilders_preservesOrder() {
        PollingSynchronizerEntry polling = DataSystemComponents.pollingSynchronizer();
        StreamingSynchronizerEntry streaming = DataSystemComponents.streamingSynchronizer();
        List<SynchronizerEntry> entries = Arrays.asList(polling, streaming);
        List<DataSourceBuilder<Synchronizer>> builders = FDv2EntryConverter.toSynchronizerBuilders(entries);
        assertEquals(2, builders.size());
        assertNotNull(builders.get(0));
        assertNotNull(builders.get(1));
    }

    @Test
    public void toSynchronizerBuilders_emptyList() {
        assertTrue(FDv2EntryConverter.toSynchronizerBuilders(Collections.emptyList()).isEmpty());
    }
}
