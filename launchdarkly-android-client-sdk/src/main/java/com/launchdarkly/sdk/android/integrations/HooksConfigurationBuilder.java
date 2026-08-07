package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.subsystems.HookConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Contains methods for configuring the SDK's 'hooks'.
 * <p>
 * If you want to add hooks, use {@link Components#hooks()}, configure accordingly, and pass it
 * to {@link com.launchdarkly.sdk.android.LDConfig.Builder#hooks(HooksConfigurationBuilder)}.
 *
 * <pre><code>
 *     List hooks = createSomeHooks();
 *     LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
 *         .hooks(
 *             Components.hooks()
 *                 .setHooks(hooks)
 *         )
 *         .build();
 * </code></pre>
 * <p>
 * A hook observes every evaluation unless it carries an exposure deduplication policy, which limits
 * how often repeated evaluations resolving to the same result reach it. See
 * {@link Hook#evaluationExposureDeduper()}.
 *
 * <pre><code>
 *     Components.hooks()
 *         .addHook(new MetricsHook())
 *         .addHook(new ObservabilityHook().evaluationExposureDeduper())
 *         .addHook(new TelemetryHook().evaluationExposureDeduper(60_000))
 *         .addHook(new ExperimentHook().evaluationExposureDeduper(myCustomDeduper))
 * </code></pre>
 * <p>
 * Note that this class is abstract; the actual implementation is created by calling {@link Components#hooks()}.
 */
public abstract class HooksConfigurationBuilder {

    /**
     * The current set of hooks the builder has.
     */
    protected List<Hook> hooks = Collections.emptyList();

    /**
     * Adds the provided list of hooks to the configuration.  Note that the order of hooks is important and controls
     * the order in which they will be executed.  See {@link Hook} for more details.
     *
     * @param hooks to be added to the configuration
     * @return the builder
     */
    public HooksConfigurationBuilder setHooks(List<Hook> hooks) {
        // copy to avoid list manipulations impacting the SDK
        this.hooks = Collections.unmodifiableList(new ArrayList<>(hooks));
        return this;
    }

    /**
     * Adds a hook to the configuration.  Note that the order of hooks is important and controls the order in which
     * they will be executed.  See {@link Hook} for more details.
     *
     * @param hook to be added to the configuration
     * @return the builder
     */
    public HooksConfigurationBuilder addHook(Hook hook) {
        List<Hook> hooks = new ArrayList<>(this.hooks);
        hooks.add(hook);
        return setHooks(hooks);
    }

    /**
     * @return the hooks configuration
     */
    abstract public HookConfiguration build();
}
