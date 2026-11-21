package com.launchdarkly.sdk.android.integrations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.android.DataSystemComponents;

import org.junit.Test;

public class ConnectionModeBuilderTest {

    @Test
    public void initializers_lastCallReplacesPrevious() {
        InitializerEntry first = DataSystemComponents.pollingInitializer();
        InitializerEntry second = DataSystemComponents.pollingInitializer();
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .initializers(first)
                .initializers(second);
        assertEquals(1, b.getInitializerEntries().size());
        assertSame(second, b.getInitializerEntries().get(0));
    }

    @Test
    public void synchronizers_lastCallReplacesPrevious() {
        SynchronizerEntry first = DataSystemComponents.pollingSynchronizer();
        SynchronizerEntry second = DataSystemComponents.streamingSynchronizer();
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .synchronizers(first)
                .synchronizers(second);
        assertEquals(1, b.getSynchronizerEntries().size());
        assertSame(second, b.getSynchronizerEntries().get(0));
    }

    @Test
    public void getInitializerEntries_isUnmodifiable() {
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .initializers(DataSystemComponents.pollingInitializer());
        try {
            b.getInitializerEntries().clear();
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void getSynchronizerEntries_isUnmodifiable() {
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .synchronizers(DataSystemComponents.pollingSynchronizer());
        try {
            b.getSynchronizerEntries().clear();
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void freshBuilder_hasEmptyLists() {
        ConnectionModeBuilder b = DataSystemComponents.customMode();
        assertEquals(0, b.getInitializerEntries().size());
        assertEquals(0, b.getSynchronizerEntries().size());
    }

    @Test
    public void freshBuilder_neitherConfiguresNorDisablesFdv1Fallback() {
        ConnectionModeBuilder b = DataSystemComponents.customMode();
        assertNull(b.getFdv1FallbackSynchronizerEntry());
        assertFalse(b.isFdv1FallbackDisabled());
    }

    @Test
    public void disableFdv1Fallback_replacesConfiguredFdv1FallbackSynchronizer() {
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .fdv1FallbackSynchronizer(DataSystemComponents.fdv1FallbackSynchronizer())
                .disableFdv1Fallback();
        assertNull(b.getFdv1FallbackSynchronizerEntry());
        assertTrue(b.isFdv1FallbackDisabled());
    }

    @Test
    public void fdv1FallbackSynchronizer_replacesEarlierDisable() {
        FDv1FallbackSynchronizerEntry fallback = DataSystemComponents.fdv1FallbackSynchronizer();
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .disableFdv1Fallback()
                .fdv1FallbackSynchronizer(fallback);
        assertSame(fallback, b.getFdv1FallbackSynchronizerEntry());
        assertFalse(b.isFdv1FallbackDisabled());
    }
}
