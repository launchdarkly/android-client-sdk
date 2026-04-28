package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.android.integrations.AutomaticModeSwitchingConfig;
import com.launchdarkly.sdk.android.integrations.DataSystemBuilder;

import org.junit.Test;

import java.util.Map;

public class DataSystemBuilderTest {

    @Test
    public void defaultBuilder_hasExpectedConnectionModesAndAutoSwitching() {
        DataSystemBuilder b = Components.dataSystem();
        assertSame(ConnectionMode.STREAMING, b.getForegroundConnectionMode());
        assertSame(ConnectionMode.BACKGROUND, b.getBackgroundConnectionMode());
        AutomaticModeSwitchingConfig auto = b.getAutomaticModeSwitchingConfig();
        assertTrue(auto.isLifecycle());
        assertTrue(auto.isNetwork());
        assertTrue(b.getConnectionModeOverrides().isEmpty());
    }

    @Test
    public void buildModeTable_containsAllStandardModes() {
        Map<ConnectionMode, ModeDefinition> table = Components.dataSystem().buildModeTable(false);
        assertEquals(5, table.size());
        assertTrue(table.containsKey(ConnectionMode.STREAMING));
        assertTrue(table.containsKey(ConnectionMode.POLLING));
        assertTrue(table.containsKey(ConnectionMode.OFFLINE));
        assertTrue(table.containsKey(ConnectionMode.ONE_SHOT));
        assertTrue(table.containsKey(ConnectionMode.BACKGROUND));
    }

    @Test
    public void buildModeTable_defaultInitializerAndSynchronizerCounts() {
        Map<ConnectionMode, ModeDefinition> table = Components.dataSystem().buildModeTable(false);
        assertEquals(2, table.get(ConnectionMode.STREAMING).getInitializers().size());
        assertEquals(2, table.get(ConnectionMode.STREAMING).getSynchronizers().size());
        assertEquals(1, table.get(ConnectionMode.POLLING).getInitializers().size());
        assertEquals(1, table.get(ConnectionMode.POLLING).getSynchronizers().size());
        assertEquals(1, table.get(ConnectionMode.OFFLINE).getInitializers().size());
        assertEquals(0, table.get(ConnectionMode.OFFLINE).getSynchronizers().size());
        assertEquals(2, table.get(ConnectionMode.ONE_SHOT).getInitializers().size());
        assertEquals(0, table.get(ConnectionMode.ONE_SHOT).getSynchronizers().size());
        assertEquals(1, table.get(ConnectionMode.BACKGROUND).getInitializers().size());
        assertEquals(1, table.get(ConnectionMode.BACKGROUND).getSynchronizers().size());
    }

    @Test
    public void customizeConnectionMode_replacesModeDefinition() {
        DataSystemBuilder b = Components.dataSystem()
                .customizeConnectionMode(
                        ConnectionMode.BACKGROUND,
                        DataSystemComponents.customMode());
        Map<ConnectionMode, ModeDefinition> table = b.buildModeTable(false);
        ModeDefinition bg = table.get(ConnectionMode.BACKGROUND);
        assertTrue(bg.getInitializers().isEmpty());
        assertTrue(bg.getSynchronizers().isEmpty());
        assertNull(bg.getFdv1FallbackSynchronizer());
    }

    @Test
    public void disableBackgroundUpdating_clearsBackgroundPipelineEvenWithOverride() {
        DataSystemBuilder b = Components.dataSystem()
                .customizeConnectionMode(
                        ConnectionMode.BACKGROUND,
                        DataSystemComponents.customMode()
                                .initializers(DataSystemComponents.pollingInitializer())
                                .synchronizers(DataSystemComponents.pollingSynchronizer()));
        Map<ConnectionMode, ModeDefinition> table = b.buildModeTable(true);
        ModeDefinition bg = table.get(ConnectionMode.BACKGROUND);
        assertTrue(bg.getInitializers().isEmpty());
        assertTrue(bg.getSynchronizers().isEmpty());
        assertNull(bg.getFdv1FallbackSynchronizer());
    }

    @Test
    public void customizeConnectionMode_preservesFdv1FallbackWhenModeHasSynchronizers() {
        DataSystemBuilder b = Components.dataSystem()
                .customizeConnectionMode(
                        ConnectionMode.STREAMING,
                        DataSystemComponents.customMode()
                                .synchronizers(DataSystemComponents.pollingSynchronizer()));
        Map<ConnectionMode, ModeDefinition> table = b.buildModeTable(false);
        ModeDefinition streaming = table.get(ConnectionMode.STREAMING);
        assertEquals(0, streaming.getInitializers().size());
        assertEquals(1, streaming.getSynchronizers().size());
        assertNotNull(streaming.getFdv1FallbackSynchronizer());
    }

    @Test
    public void customizeConnectionMode_preservesFdv1FallbackWhenModeHasOnlyInitializers() {
        DataSystemBuilder b = Components.dataSystem()
                .customizeConnectionMode(
                        ConnectionMode.STREAMING,
                        DataSystemComponents.customMode()
                                .initializers(DataSystemComponents.pollingInitializer()));
        Map<ConnectionMode, ModeDefinition> table = b.buildModeTable(false);
        ModeDefinition streaming = table.get(ConnectionMode.STREAMING);
        assertEquals(1, streaming.getInitializers().size());
        assertEquals(0, streaming.getSynchronizers().size());
        assertNotNull(streaming.getFdv1FallbackSynchronizer());
    }

    @Test
    public void customizeConnectionMode_nullsFdv1FallbackWhenModeIsEmpty() {
        DataSystemBuilder b = Components.dataSystem()
                .customizeConnectionMode(
                        ConnectionMode.STREAMING,
                        DataSystemComponents.customMode());
        Map<ConnectionMode, ModeDefinition> table = b.buildModeTable(false);
        ModeDefinition streaming = table.get(ConnectionMode.STREAMING);
        assertTrue(streaming.getInitializers().isEmpty());
        assertTrue(streaming.getSynchronizers().isEmpty());
        assertNull(streaming.getFdv1FallbackSynchronizer());
    }

    @Test
    public void getConnectionModeOverrides_isUnmodifiable() {
        DataSystemBuilder b = Components.dataSystem();
        try {
            b.getConnectionModeOverrides().put(ConnectionMode.OFFLINE, DataSystemComponents.customMode());
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void foregroundAndBackgroundModes_areReflectedInBuilderGetters() {
        DataSystemBuilder b = Components.dataSystem()
                .foregroundConnectionMode(ConnectionMode.POLLING)
                .backgroundConnectionMode(ConnectionMode.ONE_SHOT);
        assertSame(ConnectionMode.POLLING, b.getForegroundConnectionMode());
        assertSame(ConnectionMode.ONE_SHOT, b.getBackgroundConnectionMode());
    }

    @Test
    public void disableBackgroundUpdating_doesNotClearOtherModes() {
        Map<ConnectionMode, ModeDefinition> table = Components.dataSystem().buildModeTable(true);
        assertEquals(2, table.get(ConnectionMode.STREAMING).getSynchronizers().size());
    }

    @Test
    public void automaticModeSwitching_roundTripsThroughGetter() {
        AutomaticModeSwitchingConfig granular = DataSystemComponents.automaticModeSwitching()
                .lifecycle(false)
                .network(true)
                .build();
        DataSystemBuilder b = Components.dataSystem().automaticModeSwitching(granular);
        AutomaticModeSwitchingConfig got = b.getAutomaticModeSwitchingConfig();
        assertEquals(granular.isLifecycle(), got.isLifecycle());
        assertEquals(granular.isNetwork(), got.isNetwork());
    }
}
