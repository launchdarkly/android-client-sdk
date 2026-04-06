package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.CachedFlagStore;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class FDv2CacheInitializerTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);

    private static final LDContext CONTEXT = LDContext.create("test-user");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    // ---- cache hit ----

    @Test
    public void cacheHit_returnsChangeSetWithFlags() throws Exception {
        Map<String, Flag> flags = new HashMap<>();
        flags.put("flag1", new FlagBuilder("flag1").version(1).value(true).build());
        flags.put("flag2", new FlagBuilder("flag2").version(2).value(LDValue.of("hello")).build());

        CachedFlagStore store = context -> new HashMap<>(flags);
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                store, CONTEXT, executor, LDLogger.none());

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertNotNull(result.getChangeSet());
        assertEquals(2, result.getChangeSet().getData().size());
        assertTrue(result.getChangeSet().getData().containsKey("flag1"));
        assertTrue(result.getChangeSet().getData().containsKey("flag2"));
    }

    @Test
    public void cacheHit_changeSetHasEmptySelector() throws Exception {
        Map<String, Flag> flags = new HashMap<>();
        flags.put("flag1", new FlagBuilder("flag1").version(1).build());

        CachedFlagStore store = context -> flags;
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                store, CONTEXT, executor, LDLogger.none());

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertTrue(result.getChangeSet().getSelector().isEmpty());
    }

    @Test
    public void cacheHit_changeSetHasFullType() throws Exception {
        CachedFlagStore store = context -> new HashMap<>();
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                store, CONTEXT, executor, LDLogger.none());

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertEquals(ChangeSetType.Full, result.getChangeSet().getType());
    }

    @Test
    public void cacheHit_changeSetHasPersistFalse() throws Exception {
        CachedFlagStore store = context -> new HashMap<>();
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                store, CONTEXT, executor, LDLogger.none());

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertFalse(result.getChangeSet().shouldPersist());
    }

    @Test
    public void cacheHit_fdv1FallbackIsFalse() throws Exception {
        CachedFlagStore store = context -> new HashMap<>();
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                store, CONTEXT, executor, LDLogger.none());

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertFalse(result.isFdv1Fallback());
    }

    // ---- cache miss ----

    @Test
    public void cacheMiss_returnsInterruptedStatus() throws Exception {
        CachedFlagStore store = context -> null;
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                store, CONTEXT, executor, LDLogger.none());

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
        assertFalse(result.isFdv1Fallback());
    }

    // ---- no persistent store ----

    @Test
    public void noPersistentStore_returnsInterruptedStatus() throws Exception {
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                null, CONTEXT, executor, LDLogger.none());

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
    }

    // ---- exception during cache read ----

    @Test
    public void exceptionDuringCacheRead_returnsInterruptedStatus() throws Exception {
        CachedFlagStore store = context -> {
            throw new RuntimeException("corrupt data");
        };
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                store, CONTEXT, executor, LDLogger.none());

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
    }

    // ---- close() behavior ----

    @Test
    public void closeBeforeRun_returnsShutdown() throws Exception {
        CachedFlagStore store = context -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        };
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                store, CONTEXT, executor, LDLogger.none());

        initializer.close();
        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());
    }

    @Test
    public void closeAfterCompletion_doesNotThrow() throws Exception {
        CachedFlagStore store = context -> new HashMap<>();
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                store, CONTEXT, executor, LDLogger.none());

        Future<FDv2SourceResult> future = initializer.run();
        future.get(1, TimeUnit.SECONDS);
        initializer.close();
    }

    // ---- empty cache (no flags stored, but store exists) ----

    @Test
    public void emptyCacheReturnsChangeSetWithEmptyMap() throws Exception {
        CachedFlagStore store = context -> new HashMap<>();
        FDv2CacheInitializer initializer = new FDv2CacheInitializer(
                store, CONTEXT, executor, LDLogger.none());

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertTrue(result.getChangeSet().getData().isEmpty());
    }
}
