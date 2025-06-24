package com.launchdarkly.sdk.android.integrations;

import static org.easymock.EasyMock.createMock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.subsystems.PluginsConfiguration;

import org.junit.Test;

import java.util.List;

public class PluginConfigurationBuilderTest {
    @Test
    public void emptyPluginsAsDefault() {
        PluginsConfiguration configuration = Components.plugins().build();
        assertEquals(0, configuration.getPlugins().size());
    }

    @Test
    public void canSetPlugins() {
        Plugin pluginA = createMock(Plugin.class);
        Plugin pluginB = createMock(Plugin.class);
        PluginsConfiguration configuration = Components.plugins().setPlugins(List.of(pluginA, pluginB)).build();
        assertEquals(2, configuration.getPlugins().size());
        assertSame(pluginA, configuration.getPlugins().get(0));
        assertSame(pluginB, configuration.getPlugins().get(1));
    }
}
