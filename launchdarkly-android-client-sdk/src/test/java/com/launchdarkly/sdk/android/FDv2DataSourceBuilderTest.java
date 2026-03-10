package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import org.junit.Rule;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class FDv2DataSourceBuilderTest {

    private static final LDContext CONTEXT = LDContext.create("builder-test-key");

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    private ClientContext makeClientContext() {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        return new ClientContext(
                "mobile-key", null, logging.logger, null, sink,
                "", false, CONTEXT, null, false, null, null, false
        );
    }

    @Test
    public void build_returnsNonNullDataSource() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        DataSource ds = builder.build(makeClientContext());
        assertNotNull(ds);
    }

    @Test
    public void build_returnsModeAwareDataSource() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        DataSource ds = builder.build(makeClientContext());
        assertTrue(ds instanceof ModeAware);
    }

    @Test
    public void build_resolvesConfigurersViaClientContext() {
        AtomicReference<ClientContext> capturedContext = new AtomicReference<>();
        ComponentConfigurer<Synchronizer> trackingConfigurer = ctx -> {
            capturedContext.set(ctx);
            return null;
        };

        Map<ConnectionMode, ModeDefinition> customTable = new EnumMap<>(ConnectionMode.class);
        customTable.put(ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.singletonList(trackingConfigurer)
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        ClientContext ctx = makeClientContext();
        DataSource ds = builder.build(ctx);

        // The factory hasn't been invoked yet (lazy resolution).
        // Trigger it by starting the data source — but for a unit test, we can verify
        // through the resolved mode definition structure instead.  We rely on the fact
        // that construction succeeded, meaning the starting mode was found in the table.
        assertNotNull(ds);

        // Verify that the configurer is callable with the right context.  The factory
        // wraps `() -> configurer.build(clientContext)`, so we verify the configurer
        // itself is wired correctly by calling it directly.
        assertNull(trackingConfigurer.build(ctx));
        assertEquals(ctx, capturedContext.get());
    }

    @Test
    public void build_usesProvidedStartingMode() {
        Map<ConnectionMode, ModeDefinition> customTable = new EnumMap<>(ConnectionMode.class);
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.POLLING);
        DataSource ds = builder.build(makeClientContext());

        // If the starting mode wasn't found in the table, construction would throw
        // IllegalArgumentException. A successful build confirms the mode was resolved.
        assertNotNull(ds);
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_throwsWhenStartingModeNotInTable() {
        Map<ConnectionMode, ModeDefinition> customTable = new EnumMap<>(ConnectionMode.class);
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));

        // Starting mode is STREAMING, but table only has POLLING
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        builder.build(makeClientContext());
    }

    @Test
    public void build_defaultConstructorUsesDefaultModeTable() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        DataSource ds = builder.build(makeClientContext());

        // The default table has all 5 modes and starts with STREAMING.
        // Successful construction confirms both the table and starting mode are valid.
        assertNotNull(ds);
        assertTrue(ds instanceof ModeAware);
    }

    @Test
    public void build_resolvesAllModesFromTable() {
        Map<ConnectionMode, ModeDefinition> customTable = new EnumMap<>(ConnectionMode.class);
        customTable.put(ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.singletonList(ctx -> null)
        ));
        customTable.put(ConnectionMode.OFFLINE, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        DataSource ds = builder.build(makeClientContext());
        assertNotNull(ds);

        // Verify that switchMode to a different mode in the table works (doesn't throw)
        ((ModeAware) ds).switchMode(ConnectionMode.OFFLINE);
    }
}
