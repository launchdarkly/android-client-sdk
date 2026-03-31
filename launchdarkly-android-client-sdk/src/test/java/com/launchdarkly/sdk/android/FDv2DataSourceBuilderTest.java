package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import org.junit.Rule;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

public class FDv2DataSourceBuilderTest {

    private static final LDContext CONTEXT = LDContext.create("test-context");
    private static final IEnvironmentReporter ENV_REPORTER = new EnvironmentReporterBuilder().build();

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    private ClientContext makeClientContext() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).build();
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        return new ClientContext(
                "mobile-key",
                ENV_REPORTER,
                logging.logger,
                config,
                sink,
                "default",
                false,
                CONTEXT,
                null,
                false,
                null,
                config.serviceEndpoints,
                false
        );
    }

    @Test
    public void defaultBuilder_buildsFDv2DataSource() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        DataSource ds = builder.build(makeClientContext());
        assertNotNull(ds);
        assertTrue(ds instanceof FDv2DataSource);
    }

    @Test
    public void customModeTable_buildsCorrectly() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null),
                null
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.POLLING);
        DataSource ds = builder.build(makeClientContext());
        assertNotNull(ds);
    }

    @Test
    public void startingMode_notInTable_throws() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null),
                null
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        try {
            builder.build(makeClientContext());
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("not found in mode table"));
        }
    }

    @Test
    public void setActiveMode_buildUsesSpecifiedMode() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>singletonList(ctx -> null),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null),
                null
        ));
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null),
                null
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        builder.setActiveMode(ConnectionMode.POLLING, true);
        DataSource ds = builder.build(makeClientContext());
        assertNotNull(ds);
    }

    @Test
    public void setActiveMode_withoutInitializers_buildsWithEmptyInitializers() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>singletonList(ctx -> null),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null),
                null
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        builder.setActiveMode(ConnectionMode.STREAMING, false);
        DataSource ds = builder.build(makeClientContext());
        assertNotNull(ds);
    }

    @Test
    public void defaultBehavior_usesStartingMode() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>singletonList(ctx -> null),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null),
                null
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        DataSource ds = builder.build(makeClientContext());
        assertNotNull(ds);
    }

    @Test
    public void getModeDefinition_returnsCorrectDefinition() {
        ModeDefinition streamingDef = new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>singletonList(ctx -> null),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null),
                null
        );
        ModeDefinition pollingDef = new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null),
                null
        );

        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.STREAMING, streamingDef);
        customTable.put(ConnectionMode.POLLING, pollingDef);

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        assertEquals(streamingDef, builder.getModeDefinition(ConnectionMode.STREAMING));
        assertEquals(pollingDef, builder.getModeDefinition(ConnectionMode.POLLING));
        assertNull(builder.getModeDefinition(ConnectionMode.OFFLINE));
    }

    @Test
    public void getModeDefinition_sameObjectUsedForEquivalenceCheck() {
        ModeDefinition sharedDef = new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null),
                null
        );

        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.STREAMING, sharedDef);
        customTable.put(ConnectionMode.POLLING, sharedDef);

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        // Identity check: same ModeDefinition object shared across modes enables 5.3.8 equivalence
        assertTrue(builder.getModeDefinition(ConnectionMode.STREAMING)
                == builder.getModeDefinition(ConnectionMode.POLLING));
    }

    @Test
    public void close_shutsDownSharedExecutor() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        // build() lazily creates the sharedExecutor
        builder.build(makeClientContext());

        // Access the executor via reflection to verify shutdown
        ScheduledExecutorService executor = getSharedExecutor(builder);
        assertNotNull(executor);
        assertFalse(executor.isShutdown());

        builder.close();
        assertTrue(executor.isShutdown());
    }

    @Test
    public void build_reusesSharedExecutorAcrossRebuilds() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        builder.build(makeClientContext());
        ScheduledExecutorService first = getSharedExecutor(builder);

        builder.build(makeClientContext());
        ScheduledExecutorService second = getSharedExecutor(builder);

        assertSame(first, second);
        assertFalse(first.isShutdown());
        builder.close();
    }

    @Test
    public void close_isIdempotent() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        builder.build(makeClientContext());

        builder.close();
        // second close should not throw
        builder.close();
    }

    private static ScheduledExecutorService getSharedExecutor(FDv2DataSourceBuilder builder) {
        try {
            java.lang.reflect.Field f = FDv2DataSourceBuilder.class.getDeclaredField("sharedExecutor");
            f.setAccessible(true);
            return (ScheduledExecutorService) f.get(builder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Default mode table FDv1 fallback slot configuration ----

    @Test
    public void defaultModeTable_streamingHasFdv1Fallback() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        builder.build(makeClientContext());

        ModeDefinition streaming = builder.getModeDefinition(ConnectionMode.STREAMING);
        assertNotNull(streaming);
        assertEquals(1, streaming.getInitializers().size());
        assertEquals(2, streaming.getSynchronizers().size());
        assertNotNull(streaming.getFdv1FallbackSynchronizer());
    }

    @Test
    public void defaultModeTable_pollingHasFdv1Fallback() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        builder.build(makeClientContext());

        ModeDefinition polling = builder.getModeDefinition(ConnectionMode.POLLING);
        assertNotNull(polling);
        assertEquals(0, polling.getInitializers().size());
        assertEquals(1, polling.getSynchronizers().size());
        assertNotNull(polling.getFdv1FallbackSynchronizer());
    }

    @Test
    public void defaultModeTable_backgroundHasFdv1Fallback() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        builder.build(makeClientContext());

        ModeDefinition background = builder.getModeDefinition(ConnectionMode.BACKGROUND);
        assertNotNull(background);
        assertEquals(0, background.getInitializers().size());
        assertEquals(1, background.getSynchronizers().size());
        assertNotNull(background.getFdv1FallbackSynchronizer());
    }

    @Test
    public void defaultModeTable_offlineHasNoFdv1Fallback() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        builder.build(makeClientContext());

        ModeDefinition offline = builder.getModeDefinition(ConnectionMode.OFFLINE);
        assertNotNull(offline);
        assertEquals(0, offline.getInitializers().size());
        assertEquals(0, offline.getSynchronizers().size());
        assertNull(offline.getFdv1FallbackSynchronizer());
    }

    @Test
    public void defaultModeTable_oneShotHasNoFdv1Fallback() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        builder.build(makeClientContext());

        ModeDefinition oneShot = builder.getModeDefinition(ConnectionMode.ONE_SHOT);
        assertNotNull(oneShot);
        assertEquals(1, oneShot.getInitializers().size());
        assertEquals(0, oneShot.getSynchronizers().size());
        assertNull(oneShot.getFdv1FallbackSynchronizer());
    }

    @Test
    public void setActiveMode_notInTable_throws() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null),
                null
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        builder.setActiveMode(ConnectionMode.POLLING, true);
        try {
            builder.build(makeClientContext());
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("not found in mode table"));
        }
    }
}
