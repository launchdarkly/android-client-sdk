package com.launchdarkly.sdk.android.subsystems;

/**
 * Interface for building FDv2 initializers and synchronizers.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 *
 * @param <TDataSource> the type of data source component being built (e.g. {@link Initializer} or {@link Synchronizer})
 * @see DataSourceBuildInputs
 */
public interface DataSourceBuilder<TDataSource> {
    /**
     * Builds a data source instance based on the provided inputs.
     *
     * @param inputs the build inputs providing dependencies and configuration
     * @return the built data source instance
     */
    TDataSource build(DataSourceBuildInputs inputs);
}
