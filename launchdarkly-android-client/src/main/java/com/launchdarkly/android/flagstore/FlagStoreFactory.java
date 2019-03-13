package com.launchdarkly.android.flagstore;

import android.support.annotation.NonNull;

/**
 * This interface is used to provide a mechanism for a FlagStoreManager to create FlagStores without
 * being dependent on a concrete FlagStore class.
 */
public interface FlagStoreFactory {

    /**
     * Create a new flag store
     *
     * @param identifier identifier to associate all flags under
     * @return A new instance of a FlagStore backed by a concrete implementation.
     */
    FlagStore createFlagStore(@NonNull String identifier);

}
