package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Defines the initializer and synchronizer pipelines for a {@link ConnectionMode}.
 * <p>
 * Each mode in the {@link #DEFAULT_MODE_TABLE} maps to a {@code ModeDefinition} that
 * describes which data source components to create. At build time,
 * {@code FDv2DataSourceBuilder} resolves each {@link ComponentConfigurer} into a
 * {@link FDv2DataSource.DataSourceFactory} by applying the {@code ClientContext}.
 * <p>
 * The configurers in {@link #DEFAULT_MODE_TABLE} are currently stubbed (return null).
 * Real {@link ComponentConfigurer} implementations will be wired in when
 * {@code FDv2DataSourceBuilder} is created.
 * <p>
 * Package-private — not part of the public SDK API.
 */
final class ModeDefinition {

    // Stubbed configurer — will be replaced with real ComponentConfigurer implementations
    // in FDv2DataSourceBuilder when concrete types are wired up.
    private static final ComponentConfigurer<Initializer> STUB_INITIALIZER = clientContext -> null;
    private static final ComponentConfigurer<Synchronizer> STUB_SYNCHRONIZER = clientContext -> null;

    static final Map<ConnectionMode, ModeDefinition> DEFAULT_MODE_TABLE;

    static {
        Map<ConnectionMode, ModeDefinition> table = new EnumMap<>(ConnectionMode.class);
        // Initializer/synchronizer lists per CONNMODE spec and js-core ConnectionModeConfig.ts.
        // Stubs will be replaced with real factories (cache, polling, streaming) in FDv2DataSourceBuilder.
        table.put(ConnectionMode.STREAMING, new ModeDefinition(
                Arrays.asList(STUB_INITIALIZER, STUB_INITIALIZER),         // cache, polling
                Arrays.asList(STUB_SYNCHRONIZER, STUB_SYNCHRONIZER)        // streaming, polling
        ));
        table.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.singletonList(STUB_INITIALIZER),               // cache
                Collections.singletonList(STUB_SYNCHRONIZER)               // polling
        ));
        table.put(ConnectionMode.OFFLINE, new ModeDefinition(
                Collections.singletonList(STUB_INITIALIZER),               // cache
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));
        table.put(ConnectionMode.ONE_SHOT, new ModeDefinition(
                Arrays.asList(STUB_INITIALIZER, STUB_INITIALIZER, STUB_INITIALIZER), // cache, polling, streaming
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));
        table.put(ConnectionMode.BACKGROUND, new ModeDefinition(
                Collections.singletonList(STUB_INITIALIZER),               // cache
                Collections.singletonList(STUB_SYNCHRONIZER)               // polling (LDConfig.DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS)
        ));
        DEFAULT_MODE_TABLE = Collections.unmodifiableMap(table);
    }

    private final List<ComponentConfigurer<Initializer>> initializers;
    private final List<ComponentConfigurer<Synchronizer>> synchronizers;

    ModeDefinition(
            @NonNull List<ComponentConfigurer<Initializer>> initializers,
            @NonNull List<ComponentConfigurer<Synchronizer>> synchronizers
    ) {
        this.initializers = Collections.unmodifiableList(initializers);
        this.synchronizers = Collections.unmodifiableList(synchronizers);
    }

    @NonNull
    List<ComponentConfigurer<Initializer>> getInitializers() {
        return initializers;
    }

    @NonNull
    List<ComponentConfigurer<Synchronizer>> getSynchronizers() {
        return synchronizers;
    }
}
