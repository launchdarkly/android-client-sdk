package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.integrations.AutomaticModeSwitchingConfig;
import com.launchdarkly.sdk.android.integrations.DataSystemBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;

import org.junit.Test;

public class LDConfigDataSystemTest {

    private static void assertResolutionTablesAgree(
            ModeResolutionTable expected,
            ModeResolutionTable actual
    ) {
        ModeState[] states = {
                new ModeState(true, true, false),
                new ModeState(false, true, false),
                new ModeState(false, true, true),
                new ModeState(true, false, false),
                new ModeState(false, false, false),
                new ModeState(true, true, true),
        };
        for (ModeState s : states) {
            assertSame(expected.resolve(s), actual.resolve(s));
        }
    }

    @Test
    public void dataSystem_build_usesFDv2DataSourceBuilder() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey("k")
                .dataSystem(Components.dataSystem())
                .build();
        assertTrue(config.dataSource instanceof FDv2DataSourceBuilder);
    }

    @Test
    public void dataSystem_resolutionTable_matchesCreateMobileForForegroundAndBackgroundModes() {
        DataSystemBuilder ds = Components.dataSystem()
                .foregroundConnectionMode(ConnectionMode.POLLING)
                .backgroundConnectionMode(ConnectionMode.ONE_SHOT);
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey("k")
                .dataSystem(ds)
                .build();
        FDv2DataSourceBuilder fdv2 = (FDv2DataSourceBuilder) config.dataSource;
        ModeResolutionTable expected = ModeResolutionTable.createMobile(
                ConnectionMode.POLLING, ConnectionMode.ONE_SHOT);
        assertResolutionTablesAgree(expected, fdv2.getResolutionTable());
    }

    @Test
    public void dataSystem_propagatesAutomaticModeSwitchingFromDataSystemBuilder() {
        AutomaticModeSwitchingConfig granular = DataSystemComponents.automaticModeSwitching()
                .lifecycle(false)
                .network(true)
                .build();
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey("k")
                .dataSystem(Components.dataSystem().automaticModeSwitching(granular))
                .build();
        assertFalse(config.getAutomaticModeSwitchingConfig().isLifecycle());
        assertTrue(config.getAutomaticModeSwitchingConfig().isNetwork());
    }

    @Test
    public void streamingDataSource_thenDataSystem_lastCallWins() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey("k")
                .dataSource(Components.streamingDataSource())
                .dataSystem(Components.dataSystem())
                .build();
        assertTrue(config.dataSource instanceof FDv2DataSourceBuilder);
    }

    @Test
    public void dataSystem_thenStreamingDataSource_lastCallWins() {
        StreamingDataSourceBuilder streaming = Components.streamingDataSource();
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey("k")
                .dataSystem(Components.dataSystem())
                .dataSource(streaming)
                .build();
        assertSame(streaming, config.dataSource);
        assertFalse(config.dataSource instanceof FDv2DataSourceBuilder);
    }

    @Test
    public void fdv1Path_defaultAutomaticModeSwitchingIsFullyEnabled() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey("k")
                .build();
        assertTrue(config.getAutomaticModeSwitchingConfig().isLifecycle());
        assertTrue(config.getAutomaticModeSwitchingConfig().isNetwork());
    }

    @Test
    public void disableBackgroundUpdating_passesThroughToBuild_modeTableClearsBackground() {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey("k")
                .dataSystem(Components.dataSystem())
                .disableBackgroundUpdating(true)
                .build();
        assertTrue(config.isDisableBackgroundPolling());
        FDv2DataSourceBuilder fdv2 = (FDv2DataSourceBuilder) config.dataSource;
        ModeDefinition bg = fdv2.getModeDefinition(ConnectionMode.BACKGROUND);
        assertTrue(bg.getInitializers().isEmpty());
        assertTrue(bg.getSynchronizers().isEmpty());
    }
}
