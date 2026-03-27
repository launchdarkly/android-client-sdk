package com.launchdarkly.sdk.android.integrations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.launchdarkly.sdk.android.DataSystemComponents;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import org.junit.Test;

public class ConnectionModeBuilderTest {

    @Test
    public void initializers_lastCallReplacesPrevious() {
        ComponentConfigurer<Initializer> first = DataSystemComponents.pollingInitializer();
        ComponentConfigurer<Initializer> second = DataSystemComponents.pollingInitializer();
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .initializers(first)
                .initializers(second);
        assertEquals(1, b.getInitializers().size());
        assertSame(second, b.getInitializers().get(0));
    }

    @Test
    public void synchronizers_lastCallReplacesPrevious() {
        ComponentConfigurer<Synchronizer> first = DataSystemComponents.pollingSynchronizer();
        ComponentConfigurer<Synchronizer> second = DataSystemComponents.streamingSynchronizer();
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .synchronizers(first)
                .synchronizers(second);
        assertEquals(1, b.getSynchronizers().size());
        assertSame(second, b.getSynchronizers().get(0));
    }

    @Test
    public void getInitializers_isUnmodifiable() {
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .initializers(DataSystemComponents.pollingInitializer());
        try {
            b.getInitializers().clear();
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void getSynchronizers_isUnmodifiable() {
        ConnectionModeBuilder b = DataSystemComponents.customMode()
                .synchronizers(DataSystemComponents.pollingSynchronizer());
        try {
            b.getSynchronizers().clear();
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void freshBuilder_hasEmptyLists() {
        ConnectionModeBuilder b = DataSystemComponents.customMode();
        assertEquals(0, b.getInitializers().size());
        assertEquals(0, b.getSynchronizers().size());
    }
}
