package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuildInputs;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Package-private subclass of {@link DataSourceBuildInputs} that carries additional
 * internal-only dependencies not exposed in the public API.
 * <p>
 * This follows the same pattern as {@link ClientContextImpl} extending
 * {@link com.launchdarkly.sdk.android.subsystems.ClientContext}: the public base class
 * defines the stable contract for customer-implemented components, while this subclass
 * adds SDK-internal properties that our built-in components can access via
 * {@link #get(DataSourceBuildInputs)}.
 * <p>
 * This class is for internal SDK use only. It is not subject to any backwards
 * compatibility guarantees.
 */
final class DataSourceBuildInputsInternal extends DataSourceBuildInputs {

    @Nullable
    private final PersistentDataStoreWrapper.ReadOnlyPerEnvironmentData perEnvironmentData;

    DataSourceBuildInputsInternal(
            LDContext evaluationContext,
            ServiceEndpoints serviceEndpoints,
            HttpConfiguration http,
            boolean evaluationReasons,
            SelectorSource selectorSource,
            ScheduledExecutorService sharedExecutor,
            @NonNull File cacheDir,
            LDLogger baseLogger,
            @Nullable PersistentDataStoreWrapper.ReadOnlyPerEnvironmentData perEnvironmentData
    ) {
        super(evaluationContext, serviceEndpoints, http, evaluationReasons,
                selectorSource, sharedExecutor, cacheDir, baseLogger);
        this.perEnvironmentData = perEnvironmentData;
    }

    /**
     * Unwraps a {@link DataSourceBuildInputs} to obtain the internal subclass.
     * If the instance is already a {@code DataSourceBuildInputsInternal}, it is
     * returned directly. Otherwise a wrapper is created with null internal fields.
     */
    static DataSourceBuildInputsInternal get(DataSourceBuildInputs inputs) {
        if (inputs instanceof DataSourceBuildInputsInternal) {
            return (DataSourceBuildInputsInternal) inputs;
        }
        return new DataSourceBuildInputsInternal(
                inputs.getEvaluationContext(),
                inputs.getServiceEndpoints(),
                inputs.getHttp(),
                inputs.isEvaluationReasons(),
                inputs.getSelectorSource(),
                inputs.getSharedExecutor(),
                inputs.getCacheDir(),
                inputs.getBaseLogger(),
                null
        );
    }

    @Nullable
    PersistentDataStoreWrapper.ReadOnlyPerEnvironmentData getPerEnvironmentDataIfAvailable() {
        return perEnvironmentData;
    }
}
