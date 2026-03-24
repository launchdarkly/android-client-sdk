package com.launchdarkly.sdk.android.integrations;

/**
 * Granular control over which events trigger automatic connection mode switches.
 * <p>
 * By default, the SDK automatically transitions between connection modes in response to
 * both lifecycle events (foreground/background) and network availability changes. This
 * class allows enabling or disabling each independently.
 * <p>
 * Use {@link com.launchdarkly.sdk.android.DataSystemComponents#automaticModeSwitching()}
 * for granular control, or the convenience factories {@link #enabled()} and {@link #disabled()},
 * and pass the result to {@link DataSystemBuilder#automaticModeSwitching(AutomaticModeSwitchingConfig)}
 * on {@link com.launchdarkly.sdk.android.Components#dataSystem()}.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * <b>Examples:</b>
 * <pre><code>
 *     // Disable all automatic mode switching (SDK stays in the connection mode it starts in)
 *     LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
 *         .mobileKey("my-key")
 *         .dataSystem(
 *             Components.dataSystem()
 *                 .automaticModeSwitching(AutomaticModeSwitchingConfig.disabled()))
 *         .build();
 * 
 *     // Granular: disable lifecycle switching, keep network switching
 *     LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
 *         .mobileKey("my-key")
 *         .dataSystem(
 *             Components.dataSystem()
 *                 .automaticModeSwitching(
 *                     DataSystemComponents.automaticModeSwitching()
 *                         .lifecycle(false)
 *                         .network(true)
 *                         .build()))
 *         .build();
 * </code></pre>
 *
 * @see com.launchdarkly.sdk.android.Components#dataSystem()
 * @see com.launchdarkly.sdk.android.DataSystemComponents#automaticModeSwitching()
 * @see DataSystemBuilder#automaticModeSwitching(AutomaticModeSwitchingConfig)
 * @see com.launchdarkly.sdk.android.LDConfig.Builder#dataSystem(DataSystemBuilder)
 */
public final class AutomaticModeSwitchingConfig {

    private final boolean lifecycle;
    private final boolean network;

    private AutomaticModeSwitchingConfig(boolean lifecycle, boolean network) {
        this.lifecycle = lifecycle;
        this.network = network;
    }

    /**
     * Returns a config with both lifecycle and network switching enabled.
     *
     * @return the enabled config
     */
    public static AutomaticModeSwitchingConfig enabled() {
        return new AutomaticModeSwitchingConfig(true, true);
    }

    /**
     * Returns a config with both lifecycle and network switching disabled.
     *
     * @return the disabled config
     */
    public static AutomaticModeSwitchingConfig disabled() {
        return new AutomaticModeSwitchingConfig(false, false);
    }

    /**
     * Whether the SDK automatically switches connection modes in response to application
     * lifecycle events (foreground/background transitions).
     * <p>
     * When true, the SDK transitions to the
     * {@link DataSystemBuilder#backgroundConnectionMode background connection mode} when
     * the app goes to the background, and back to the
     * {@link DataSystemBuilder#foregroundConnectionMode foreground connection mode} when
     * the app returns to the foreground.
     *
     * @return true if lifecycle-based mode switching is enabled
     */
    public boolean isLifecycle() {
        return lifecycle;
    }

    /**
     * Whether the SDK automatically switches connection modes in response to network
     * availability changes.
     * <p>
     * When true, the SDK transitions to {@link com.launchdarkly.sdk.android.ConnectionMode#OFFLINE}
     * when network connectivity is lost, and back to the appropriate mode when connectivity
     * is restored.
     *
     * @return true if network-based mode switching is enabled
     */
    public boolean isNetwork() {
        return network;
    }

    /**
     * Builder for {@link AutomaticModeSwitchingConfig}.
     * <p>
     * Obtain an instance from
     * {@link com.launchdarkly.sdk.android.DataSystemComponents#automaticModeSwitching()}.
     * Both {@code lifecycle} and {@code network} default to {@code true}.
     *
     * @see com.launchdarkly.sdk.android.DataSystemComponents#automaticModeSwitching()
     */
    public static final class Builder {
        private boolean lifecycle = true;
        private boolean network = true;

        /**
         * Creates a builder with lifecycle and network switching both enabled by default.
         * <p>
         * Prefer {@link com.launchdarkly.sdk.android.DataSystemComponents#automaticModeSwitching()}
         * as the entry point for constructing builders.
         */
        public Builder() {}

        /**
         * Sets whether lifecycle-based mode switching is enabled.
         *
         * @param lifecycle true to enable (default), false to disable
         * @return this builder
         */
        public Builder lifecycle(boolean lifecycle) {
            this.lifecycle = lifecycle;
            return this;
        }

        /**
         * Sets whether network-based mode switching is enabled.
         *
         * @param network true to enable (default), false to disable
         * @return this builder
         */
        public Builder network(boolean network) {
            this.network = network;
            return this;
        }

        /**
         * Builds the config.
         *
         * @return the constructed config
         */
        public AutomaticModeSwitchingConfig build() {
            return new AutomaticModeSwitchingConfig(lifecycle, network);
        }
    }
}
