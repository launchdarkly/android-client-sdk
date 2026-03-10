package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.android.subsystems.TransactionalDataStore;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.Selector;

import org.junit.Rule;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class FDv2DataSourceBuilderTest {

    private static final LDContext CONTEXT = LDContext.create("builder-test-key");
    private static final IEnvironmentReporter ENV_REPORTER = new EnvironmentReporterBuilder().build();

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    /**
     * Creates a minimal ClientContext for tests that use a custom mode table.
     * No TransactionalDataStore or HTTP config needed — the custom path
     * only wraps ComponentConfigurers in DataSourceFactory lambdas.
     */
    private ClientContext makeMinimalClientContext() {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        return new ClientContext(
                "mobile-key", null, logging.logger, null, sink,
                "", false, CONTEXT, null, false, null, null, false
        );
    }

    /**
     * Creates a ClientContext backed by a real ClientContextImpl with HTTP config,
     * ServiceEndpoints, and a TransactionalDataStore. Used by tests that exercise
     * the default (real-wiring) build path.
     */
    private ClientContext makeFullClientContext() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).build();
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        // Two-phase ClientContext creation: first without HTTP config to bootstrap it,
        // then with the resolved HTTP config — mirrors ClientContextImpl.fromConfig().
        ClientContext bootstrap = new ClientContext(
                "mobile-key", ENV_REPORTER, logging.logger, config,
                null, "", false, CONTEXT, null, false, null,
                config.serviceEndpoints, false
        );
        HttpConfiguration httpConfig = config.http.build(bootstrap);

        ClientContext base = new ClientContext(
                "mobile-key", ENV_REPORTER, logging.logger, config,
                sink, "", false, CONTEXT, httpConfig, false, null,
                config.serviceEndpoints, false
        );

        TransactionalDataStore mockStore = new TransactionalDataStore() {
            @Override
            public void apply(@NonNull LDContext context,
                              @NonNull ChangeSet<Map<String, Flag>> changeSet) { }

            @NonNull
            @Override
            public Selector getSelector() {
                return Selector.EMPTY;
            }
        };

        return new ClientContextImpl(base, null, null, null, null, null, mockStore);
    }

    // --- Default constructor tests (real wiring path) ---

    @Test
    public void build_defaultConstructor_returnsNonNullModeAwareDataSource() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        DataSource ds = builder.build(makeFullClientContext());
        assertNotNull(ds);
        assertTrue(ds instanceof ModeAware);
    }

    @Test
    public void build_defaultConstructor_allModesResolved() {
        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder();
        DataSource ds = builder.build(makeFullClientContext());

        ModeAware modeAware = (ModeAware) ds;
        for (ConnectionMode mode : ConnectionMode.values()) {
            modeAware.switchMode(mode);
        }
    }

    // --- Custom mode table tests (configurer resolution path) ---

    @Test
    public void build_customTable_resolvesConfigurersViaClientContext() {
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
        ClientContext ctx = makeMinimalClientContext();
        DataSource ds = builder.build(ctx);

        assertNotNull(ds);
        assertNull(trackingConfigurer.build(ctx));
        assertEquals(ctx, capturedContext.get());
    }

    @Test
    public void build_customTable_usesProvidedStartingMode() {
        Map<ConnectionMode, ModeDefinition> customTable = new EnumMap<>(ConnectionMode.class);
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.POLLING);
        DataSource ds = builder.build(makeMinimalClientContext());
        assertNotNull(ds);
    }

    @Test(expected = IllegalArgumentException.class)
    public void build_customTable_throwsWhenStartingModeNotInTable() {
        Map<ConnectionMode, ModeDefinition> customTable = new EnumMap<>(ConnectionMode.class);
        customTable.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(customTable, ConnectionMode.STREAMING);
        builder.build(makeMinimalClientContext());
    }

    @Test
    public void build_customTable_resolvesAllModesAndSupportsSwitchMode() {
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
        DataSource ds = builder.build(makeMinimalClientContext());
        assertNotNull(ds);
        ((ModeAware) ds).switchMode(ConnectionMode.OFFLINE);
    }
}
