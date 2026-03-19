package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    }

    @Test
    public void customModeTable_buildsCorrectly() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
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
    public void setActiveMode_buildUsesSpecifiedMode() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>singletonList(ctx -> null),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
        ));
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
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
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
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
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        DataSource ds = builder.build(makeClientContext());
        assertNotNull(ds);
    }

    @Test
    public void getModeDefinition_returnsCorrectDefinition() {
        ModeDefinition streamingDef = new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>singletonList(ctx -> null),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
        );
        ModeDefinition pollingDef = new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
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
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
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
    public void setActiveMode_notInTable_throws() {
        Map<ConnectionMode, ModeDefinition> customTable = new LinkedHashMap<>();
        customTable.put(ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>singletonList(ctx -> null)
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
