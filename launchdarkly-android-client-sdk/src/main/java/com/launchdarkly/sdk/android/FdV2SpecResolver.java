package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.CacheInitializerSpec;
import com.launchdarkly.sdk.android.integrations.InitializerSpec;
import com.launchdarkly.sdk.android.integrations.PollingInitializerSpec;
import com.launchdarkly.sdk.android.integrations.PollingSynchronizerSpec;
import com.launchdarkly.sdk.android.integrations.StreamingSynchronizerSpec;
import com.launchdarkly.sdk.android.integrations.SynchronizerSpec;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts FDv2 {@link com.launchdarkly.sdk.android.integrations.InitializerSpec} /
 * {@link com.launchdarkly.sdk.android.integrations.SynchronizerSpec} configuration into internal
 * {@link DataSourceBuilder} instances. Intended for use inside the SDK when building a mode table.
 */
public final class FdV2SpecResolver {

    private FdV2SpecResolver() {
    }

    public static DataSourceBuilder<Initializer> toInitializerBuilder(InitializerSpec spec) {
        if (spec instanceof CacheInitializerSpec) {
            return new DataSystemComponents.CacheInitializerBuilderImpl();
        }
        if (spec instanceof PollingInitializerSpec) {
            return DataSystemComponents.PollingInitializerBuilderImpl.fromSpec((PollingInitializerSpec) spec);
        }
        throw new IllegalArgumentException("Unsupported InitializerSpec: " + spec.getClass().getName());
    }

    public static DataSourceBuilder<Synchronizer> toSynchronizerBuilder(SynchronizerSpec spec) {
        if (spec instanceof PollingSynchronizerSpec) {
            return DataSystemComponents.PollingSynchronizerBuilderImpl.fromSpec((PollingSynchronizerSpec) spec);
        }
        if (spec instanceof StreamingSynchronizerSpec) {
            return DataSystemComponents.StreamingSynchronizerBuilderImpl.fromSpec((StreamingSynchronizerSpec) spec);
        }
        throw new IllegalArgumentException("Unsupported SynchronizerSpec: " + spec.getClass().getName());
    }

    public static List<DataSourceBuilder<Initializer>> toInitializerBuilders(List<InitializerSpec> specs) {
        List<DataSourceBuilder<Initializer>> out = new ArrayList<>(specs.size());
        for (InitializerSpec spec : specs) {
            out.add(toInitializerBuilder(spec));
        }
        return out;
    }

    public static List<DataSourceBuilder<Synchronizer>> toSynchronizerBuilders(List<SynchronizerSpec> specs) {
        List<DataSourceBuilder<Synchronizer>> out = new ArrayList<>(specs.size());
        for (SynchronizerSpec spec : specs) {
            out.add(toSynchronizerBuilder(spec));
        }
        return out;
    }
}
