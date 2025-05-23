package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.subsystems.HookConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.easymock.EasyMock.createMock;

import java.util.List;

public class HookConfigurationBuilderTest {
    @Test
    public void emptyHooksAsDefault() {
        HookConfiguration configuration = Components.hooks().build();
        assertEquals(0, configuration.getHooks().size());
    }

    @Test
    public void canSetHooks() {
        Hook hookA = createMock(Hook.class);
        Hook hookB = createMock(Hook.class);
        HookConfiguration configuration = Components.hooks().setHooks(List.of(hookA, hookB)).build();
        assertEquals(2, configuration.getHooks().size());
        assertSame(hookA, configuration.getHooks().get(0));
        assertSame(hookB, configuration.getHooks().get(1));
    }
}
