package com.launchdarkly.sdk.android.integrations;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.android.DataSystemComponents;

import org.junit.Test;

public class AutomaticModeSwitchingConfigTest {

    @Test
    public void enabled_enablesLifecycleAndNetwork() {
        AutomaticModeSwitchingConfig c = AutomaticModeSwitchingConfig.enabled();
        assertTrue(c.isLifecycle());
        assertTrue(c.isNetwork());
    }

    @Test
    public void disabled_disablesLifecycleAndNetwork() {
        AutomaticModeSwitchingConfig c = AutomaticModeSwitchingConfig.disabled();
        assertFalse(c.isLifecycle());
        assertFalse(c.isNetwork());
    }

    @Test
    public void builder_defaultsMatchEnabled() {
        AutomaticModeSwitchingConfig built = DataSystemComponents.automaticModeSwitching().build();
        AutomaticModeSwitchingConfig enabled = AutomaticModeSwitchingConfig.enabled();
        assertTrue(built.isLifecycle());
        assertTrue(built.isNetwork());
        assertTrue(enabled.isLifecycle());
        assertTrue(enabled.isNetwork());
    }

    @Test
    public void builder_lifecycleOnly() {
        AutomaticModeSwitchingConfig c = DataSystemComponents.automaticModeSwitching()
                .lifecycle(true)
                .network(false)
                .build();
        assertTrue(c.isLifecycle());
        assertFalse(c.isNetwork());
    }

    @Test
    public void builder_networkOnly() {
        AutomaticModeSwitchingConfig c = DataSystemComponents.automaticModeSwitching()
                .lifecycle(false)
                .network(true)
                .build();
        assertFalse(c.isLifecycle());
        assertTrue(c.isNetwork());
    }
}
