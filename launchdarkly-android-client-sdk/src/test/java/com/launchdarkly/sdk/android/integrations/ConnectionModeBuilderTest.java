package com.launchdarkly.sdk.android.integrations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.launchdarkly.sdk.android.DataSystemComponents;

import org.junit.Test;

public class ConnectionModeBuilderTest {

    @Test
    public void initializers_lastCallReplacesPrevious() {
        InitializerSpec first = DataSystemComponents.pollingInitializer();
        InitializerSpec second = DataSystemComponents.pollingInitializer();
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .initializers(first)
                .initializers(second);
        assertEquals(1, b.getInitializerSpecs().size());
        assertSame(second, b.getInitializerSpecs().get(0));
    }

    @Test
    public void synchronizers_lastCallReplacesPrevious() {
        SynchronizerSpec first = DataSystemComponents.pollingSynchronizer();
        SynchronizerSpec second = DataSystemComponents.streamingSynchronizer();
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .synchronizers(first)
                .synchronizers(second);
        assertEquals(1, b.getSynchronizerSpecs().size());
        assertSame(second, b.getSynchronizerSpecs().get(0));
    }

    @Test
    public void getInitializerSpecs_isUnmodifiable() {
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .initializers(DataSystemComponents.pollingInitializer());
        try {
            b.getInitializerSpecs().clear();
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void getSynchronizerSpecs_isUnmodifiable() {
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .synchronizers(DataSystemComponents.pollingSynchronizer());
        try {
            b.getSynchronizerSpecs().clear();
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void freshBuilder_hasEmptyLists() {
        ConnectionModeBuilder b = DataSystemComponents.customMode();
        assertEquals(0, b.getInitializerSpecs().size());
        assertEquals(0, b.getSynchronizerSpecs().size());
    }
}
