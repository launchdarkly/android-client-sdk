package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.SelectorSource;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Build inputs (dependencies and configuration) provided to
 * {@link DataSourceBuilder#build(DataSourceBuildInputs)} when constructing
 * FDv2 initializers and synchronizers.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * This consolidates the parameters needed to construct data source components,
 * providing a narrower contract than the full {@link ClientContext}.
 *
 * @see DataSourceBuilder
 */
public final class DataSourceBuildInputs {
    private final LDContext evaluationContext;
    private final ServiceEndpoints serviceEndpoints;
    private final HttpConfiguration http;
    private final boolean evaluationReasons;
    private final SelectorSource selectorSource;
    private final ScheduledExecutorService sharedExecutor;
    private final File cacheDir;
    @Nullable
    private final CachedFlagStore cachedFlagStore;
    private final LDLogger baseLogger;

    /**
     * Constructs a DataSourceBuildInputs.
     *
     * @param evaluationContext  the current evaluation context
     * @param serviceEndpoints  the service endpoint URIs
     * @param http              the HTTP configuration
     * @param evaluationReasons whether evaluation reasons are enabled
     * @param selectorSource    the source for obtaining the current selector
     * @param sharedExecutor    shared executor for scheduling tasks; owned and shut down by
     *                          the calling data source, so components must not shut it down
     * @param cacheDir          the platform's cache directory for HTTP-level caching
     * @param cachedFlagStore   read access to cached flag data, or null if no persistent
     *                          store is configured
     * @param baseLogger        the base logger instance
     */
    public DataSourceBuildInputs(
            LDContext evaluationContext,
            ServiceEndpoints serviceEndpoints,
            HttpConfiguration http,
            boolean evaluationReasons,
            SelectorSource selectorSource,
            ScheduledExecutorService sharedExecutor,
            @NonNull File cacheDir,
            @Nullable CachedFlagStore cachedFlagStore,
            LDLogger baseLogger
    ) {
        this.evaluationContext = evaluationContext;
        this.serviceEndpoints = serviceEndpoints;
        this.http = http;
        this.evaluationReasons = evaluationReasons;
        this.selectorSource = selectorSource;
        this.sharedExecutor = sharedExecutor;
        this.cacheDir = cacheDir;
        this.cachedFlagStore = cachedFlagStore;
        this.baseLogger = baseLogger;
    }

    /**
     * Returns the current evaluation context.
     *
     * @return the evaluation context
     */
    public LDContext getEvaluationContext() {
        return evaluationContext;
    }

    /**
     * Returns the service endpoint URIs.
     *
     * @return the service endpoints
     */
    public ServiceEndpoints getServiceEndpoints() {
        return serviceEndpoints;
    }

    /**
     * Returns the HTTP configuration.
     *
     * @return the HTTP configuration
     */
    public HttpConfiguration getHttp() {
        return http;
    }

    /**
     * Returns whether evaluation reasons are enabled.
     *
     * @return true if evaluation reasons are enabled
     */
    public boolean isEvaluationReasons() {
        return evaluationReasons;
    }

    /**
     * Returns the selector source for obtaining the current FDv2 selector.
     *
     * @return the selector source
     */
    public SelectorSource getSelectorSource() {
        return selectorSource;
    }

    /**
     * Returns the shared executor service for scheduling tasks.
     * <p>
     * This executor is owned by the parent data source and will be shut down when the
     * data source is closed. Components must not call {@code shutdown()} on it.
     *
     * @return the shared scheduled executor service
     */
    public ScheduledExecutorService getSharedExecutor() {
        return sharedExecutor;
    }

    /**
     * Returns the platform's cache directory for HTTP-level caching.
     *
     * @return the cache directory
     */
    @NonNull
    public File getCacheDir() {
        return cacheDir;
    }

    /**
     * Returns read access to cached flag data, or null if no persistent store
     * is configured. Used by the cache initializer to load stored flags.
     *
     * @return the cached flag store, or null
     */
    @Nullable
    public CachedFlagStore getCachedFlagStore() {
        return cachedFlagStore;
    }

    /**
     * Returns the base logger instance.
     *
     * @return the base logger
     */
    public LDLogger getBaseLogger() {
        return baseLogger;
    }
}
