package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

class FlagStoreImplFactory implements FlagStoreFactory {

    @NonNull private final PersistentDataStore store;
    @NonNull private final LDLogger logger;

    FlagStoreImplFactory(@NonNull PersistentDataStore store, @NonNull LDLogger logger) {
        this.store = store;
        this.logger = logger;
    }

    @Override
    public FlagStore createFlagStore(@NonNull String identifier) {
        return new FlagStoreImpl(store, identifier, logger);
    }
}
