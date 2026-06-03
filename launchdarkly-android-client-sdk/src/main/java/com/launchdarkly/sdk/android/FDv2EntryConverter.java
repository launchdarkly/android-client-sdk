package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.CacheInitializerEntry;
import com.launchdarkly.sdk.android.integrations.InitializerEntry;
import com.launchdarkly.sdk.android.integrations.PollingInitializerEntry;
import com.launchdarkly.sdk.android.integrations.PollingSynchronizerEntry;
import com.launchdarkly.sdk.android.integrations.StreamingSynchronizerEntry;
import com.launchdarkly.sdk.android.integrations.SynchronizerEntry;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts FDv2 {@link com.launchdarkly.sdk.android.integrations.InitializerEntry} /
 * {@link com.launchdarkly.sdk.android.integrations.SynchronizerEntry} configuration into internal
 * {@link DataSourceBuilder} instances. Intended for use inside the SDK when building a mode table.
 * <p>
 * Named a "converter" (not a resolver) to distinguish this from connection {@linkplain ConnectionMode mode}
 * resolution ({@link ModeResolutionTable}, etc.).
 */
public final class FDv2EntryConverter {

    private FDv2EntryConverter() {
    }

    public static DataSourceBuilder<Initializer> toInitializerBuilder(InitializerEntry entry) {
        if (entry instanceof CacheInitializerEntry) {
            return DataSystemComponents.CacheInitializerBuilderImpl.fromEntry((CacheInitializerEntry) entry);
        }
        if (entry instanceof PollingInitializerEntry) {
            return DataSystemComponents.PollingInitializerBuilderImpl.fromEntry((PollingInitializerEntry) entry);
        }
        throw new IllegalArgumentException("Unsupported InitializerEntry: " + entry.getClass().getName());
    }

    public static DataSourceBuilder<Synchronizer> toSynchronizerBuilder(SynchronizerEntry entry) {
        if (entry instanceof PollingSynchronizerEntry) {
            return DataSystemComponents.PollingSynchronizerBuilderImpl.fromEntry((PollingSynchronizerEntry) entry);
        }
        if (entry instanceof StreamingSynchronizerEntry) {
            return DataSystemComponents.StreamingSynchronizerBuilderImpl.fromEntry((StreamingSynchronizerEntry) entry);
        }
        throw new IllegalArgumentException("Unsupported SynchronizerEntry: " + entry.getClass().getName());
    }

    public static List<DataSourceBuilder<Initializer>> toInitializerBuilders(List<InitializerEntry> entries) {
        List<DataSourceBuilder<Initializer>> out = new ArrayList<>(entries.size());
        for (InitializerEntry entry : entries) {
            out.add(toInitializerBuilder(entry));
        }
        return out;
    }

    public static List<DataSourceBuilder<Synchronizer>> toSynchronizerBuilders(List<SynchronizerEntry> entries) {
        List<DataSourceBuilder<Synchronizer>> out = new ArrayList<>(entries.size());
        for (SynchronizerEntry entry : entries) {
            out.add(toSynchronizerBuilder(entry));
        }
        return out;
    }
}
