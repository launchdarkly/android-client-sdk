package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
        assertTrue(ds instanceof ModeAware);
    }

    @Test
    public void resolvedModeTable_availableAfterBuild() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        builder.build(makeClientContext());
        Map<ConnectionMode, ResolvedModeDefinition> table = builder.getResolvedModeTable();
        assertNotNull(table);
        assertEquals(5, table.size());
    }

    @Test(expected = IllegalStateException.class)
    public void resolvedModeTable_throwsBeforeBuild() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        builder.getResolvedModeTable();
    }

    @Test
    public void customModeTable_resolvesCorrectly() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.POLLING);
        DataSource ds = builder.build(makeClientContext());
        assertNotNull(ds);

        Map<ConnectionMode, ResolvedModeDefinition> table = builder.getResolvedModeTable();
        assertEquals(1, table.size());
        assertTrue(table.containsKey(ConnectionMode.POLLING));
    }

    @Test
    public void startingMode_notInTable_throws() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
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
    public void resolvedDefinition_hasSameSizeAsOriginal() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>singletonList(ctx -> null),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        builder.build(makeClientContext());

        ResolvedModeDefinition def = builder.getResolvedModeTable().get(ConnectionMode.STREAMING);
        assertNotNull(def);
        assertEquals(1, def.getInitializerFactories().size());
        assertEquals(1, def.getSynchronizerFactories().size());
    }
}
