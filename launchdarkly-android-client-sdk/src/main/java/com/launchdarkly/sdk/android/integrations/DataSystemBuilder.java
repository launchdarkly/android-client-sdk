package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.ConnectionMode;
import com.launchdarkly.sdk.android.DataSystemComponents;
import com.launchdarkly.sdk.android.ModeDefinition;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configures the SDK's data system: how and when the SDK acquires feature flag
 * data across different platform states (foreground, background, offline, etc.).
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * The data system is organized around {@link ConnectionMode connection modes}. Each
 * mode has a data pipeline consisting of <b>initializers</b> (one-shot data loads)
 * and <b>synchronizers</b> (ongoing data updates). The SDK automatically transitions
 * between modes based on platform state (foreground/background, network availability).
 * <p>
 * <b>Quick start — use defaults:</b>
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
 *         .mobileKey("my-key")
 *         .dataSystem(Components.dataSystem())
 *         .build();
 * </code></pre>
 * <p>
 * <b>Custom mode pipelines — background polling once every 6 hours:</b>
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
 *         .mobileKey("my-key")
 *         .dataSystem(
 *             Components.dataSystem()
 *                 .customizeConnectionMode(ConnectionMode.BACKGROUND,
 *                     DataSystemComponents.customMode()
 *                         .initializers(DataSystemComponents.pollingInitializer())
 *                         .synchronizers(
 *                             DataSystemComponents.pollingSynchronizer()
 *                                 .pollIntervalMillis(21_600_000))))
 *         .build();
 * </code></pre>
 * <p>
 * <b>Change the foreground mode to polling:</b>
 * <pre><code>
 *     Components.dataSystem()
 *         .foregroundConnectionMode(ConnectionMode.POLLING)
 * </code></pre>
 * <p>
 * <b>Disable automatic mode switching:</b>
 * <pre><code>
 *     Components.dataSystem()
 *         .automaticModeSwitching(AutomaticModeSwitchingConfig.disabled())
 *         .foregroundConnectionMode(ConnectionMode.STREAMING)
 * </code></pre>
 * When automatic mode switching is disabled, the SDK stays in the
 * {@link #foregroundConnectionMode foreground connection mode} and does not react to
 * platform state changes (foreground/background, network availability). This can be
 * useful when you want full control over which mode the SDK uses.
 * <p>
 * <b>Granular automatic mode switching — disable switching on lifecycle change, keep switching on network availability change:</b>
 * <pre><code>
 *     Components.dataSystem()
 *         .automaticModeSwitching(
 *             DataSystemComponents.automaticModeSwitching()
 *                 .lifecycle(false)
 *                 .network(true)
 *                 .build())
 * </code></pre>
 * <p>
 * Obtain an instance from {@link com.launchdarkly.sdk.android.Components#dataSystem()}.
 *
 * @see ConnectionMode
 * @see ConnectionModeBuilder
 * @see DataSystemComponents
 */
public class DataSystemBuilder {

    private ConnectionMode foregroundConnectionMode = ConnectionMode.STREAMING;
    private ConnectionMode backgroundConnectionMode = ConnectionMode.BACKGROUND;
    private AutomaticModeSwitchingConfig automaticModeSwitchingConfig = AutomaticModeSwitchingConfig.enabled();
    private final Map<ConnectionMode, ConnectionModeBuilder> connectionModeOverrides = new LinkedHashMap<>();

    /**
     * Sets the connection mode used when the application is in the foreground.
     * <p>
     * This determines which entry in the mode table is used when the SDK resolves
     * the foreground platform state. For instance, setting this to
     * {@link ConnectionMode#POLLING} means the SDK will use the polling mode
     * pipeline when the app is in the foreground.
     * <p>
     * The default is {@link ConnectionMode#STREAMING}.
     *
     * @param mode the foreground connection mode
     * @return this builder
     */
    public DataSystemBuilder foregroundConnectionMode(@NonNull ConnectionMode mode) {
        this.foregroundConnectionMode = mode;
        return this;
    }

    /**
     * Sets the connection mode used when the application is in the background.
     * <p>
     * The default is {@link ConnectionMode#BACKGROUND}.
     *
     * @param mode the background connection mode
     * @return this builder
     */
    public DataSystemBuilder backgroundConnectionMode(@NonNull ConnectionMode mode) {
        this.backgroundConnectionMode = mode;
        return this;
    }

    /**
     * Configures automatic mode switching based on platform state.
     * <p>
     * Automatic mode switching controls whether the SDK reacts to application lifecycle
     * events (foreground/background) and network availability changes by transitioning
     * between connection modes. Lifecycle and network switching can be controlled
     * independently via
     * {@link com.launchdarkly.sdk.android.DataSystemComponents#automaticModeSwitching()}.
     * <p>
     * The default is {@link AutomaticModeSwitchingConfig#enabled()}, meaning the SDK
     * reacts to both lifecycle and network events.
     * <p>
     * Use {@link AutomaticModeSwitchingConfig#disabled()} to disable all automatic
     * switching. When disabled, the SDK stays in the
     * {@link #foregroundConnectionMode foreground connection mode} for its entire
     * lifecycle.
     * <p>
     * Example — disable lifecycle switching but keep network switching:
     * <pre><code>
     *     Components.dataSystem()
     *         .automaticModeSwitching(
     *             DataSystemComponents.automaticModeSwitching()
     *                 .lifecycle(false)
     *                 .network(true)
     *                 .build())
     * </code></pre>
     * <p>
     *
     * @param config the automatic mode switching configuration
     * @return this builder
     * @see AutomaticModeSwitchingConfig
     * @see com.launchdarkly.sdk.android.DataSystemComponents#automaticModeSwitching()
     */
    public DataSystemBuilder automaticModeSwitching(@NonNull AutomaticModeSwitchingConfig config) {
        this.automaticModeSwitchingConfig = config;
        return this;
    }

    /**
     * Overrides the data pipeline for a specific connection mode.
     * <p>
     * This only affects the specified mode. All other connection modes that are not
     * customized continue to use their default pipelines. For example, customizing
     * {@link ConnectionMode#BACKGROUND} does not change the behavior of
     * {@link ConnectionMode#STREAMING} or any other mode.
     * <p>
     * Example — set background polling to once every 6 hours:
     * <pre><code>
     *     Components.dataSystem()
     *         .customizeConnectionMode(ConnectionMode.BACKGROUND,
     *             DataSystemComponents.customMode()
     *                 .initializers(DataSystemComponents.pollingInitializer())
     *                 .synchronizers(
     *                     DataSystemComponents.pollingSynchronizer()
     *                         .pollIntervalMillis(21_600_000)))
     * </code></pre>
     *
     * @param mode    the connection mode to customize
     * @param builder the pipeline configuration for this mode
     * @return this builder
     */
    public DataSystemBuilder customizeConnectionMode(
            @NonNull ConnectionMode mode,
            @NonNull ConnectionModeBuilder builder
    ) {
        connectionModeOverrides.put(mode, builder);
        return this;
    }

    /**
     * Returns the configured foreground connection mode.
     *
     * @return the foreground connection mode
     */
    @NonNull
    public ConnectionMode getForegroundConnectionMode() {
        return foregroundConnectionMode;
    }

    /**
     * Returns the configured background connection mode.
     *
     * @return the background connection mode
     */
    @NonNull
    public ConnectionMode getBackgroundConnectionMode() {
        return backgroundConnectionMode;
    }

    /**
     * Returns the automatic mode switching configuration.
     *
     * @return the automatic mode switching config
     */
    @NonNull
    public AutomaticModeSwitchingConfig getAutomaticModeSwitchingConfig() {
        return automaticModeSwitchingConfig;
    }

    /**
     * Returns any user-specified mode overrides.
     *
     * @return an unmodifiable map of overridden connection modes
     */
    @NonNull
    public Map<ConnectionMode, ConnectionModeBuilder> getConnectionModeOverrides() {
        return Collections.unmodifiableMap(connectionModeOverrides);
    }

    /**
     * Builds the full mode table by starting with defaults and applying any user
     * overrides and LDConfig-level settings.
     * <p>
     * If {@code disableBackgroundUpdating} is true, the background mode entry
     * is replaced with an empty pipeline (no initializers or synchronizers).
     *
     * @param disableBackgroundUpdating whether background updates are disabled
     * @return the complete mode table
     */
    @NonNull
    public Map<ConnectionMode, ModeDefinition> buildModeTable(boolean disableBackgroundUpdating) {
        Map<ConnectionMode, ModeDefinition> table = DataSystemComponents.makeDefaultModeTable();

        for (Map.Entry<ConnectionMode, ConnectionModeBuilder> entry : connectionModeOverrides.entrySet()) {
            ConnectionModeBuilder cmb = entry.getValue();

            DataSourceBuilder<Synchronizer> fdv1FallbackSynchronizer = null;
            if (!cmb.getInitializers().isEmpty() || !cmb.getSynchronizers().isEmpty()) {
                fdv1FallbackSynchronizer = table.get(entry.getKey()).getFdv1FallbackSynchronizer(); // use fdv1 fallback from default mode table
            }

            table.put(entry.getKey(), new ModeDefinition(
                    cmb.getInitializers(),
                    cmb.getSynchronizers(),
                    fdv1FallbackSynchronizer
            ));
        }

        if (disableBackgroundUpdating) {
            table.put(ConnectionMode.BACKGROUND, new ModeDefinition(
                    Collections.<DataSourceBuilder<Initializer>>emptyList(),
                    Collections.<DataSourceBuilder<Synchronizer>>emptyList(),
                    null
            ));
        }

        return table;
    }

}
