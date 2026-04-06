package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.Nullable;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.DataModel;

import java.util.Map;

/**
 * Provides read access to cached flag data for a specific evaluation context.
 * <p>
 * This interface bridges the persistence layer with FDv2 data source builders,
 * allowing the cache initializer to load stored flags without depending on
 * package-private types.
 */
public interface CachedFlagStore {
    /**
     * Returns the cached flag data for the given context, or null if no
     * cached data exists.
     *
     * @param context the evaluation context to look up
     * @return the cached flags, or null on cache miss
     */
    @Nullable
    Map<String, DataModel.Flag> getCachedFlags(LDContext context);
}
