package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;

class FlagStoreImplFactory implements FlagStoreFactory {

    @NonNull private final PersistentDataStoreWrapper.PerEnvironmentData environmentData;
    @NonNull private final LDLogger logger;

    FlagStoreImplFactory(
            @NonNull PersistentDataStoreWrapper.PerEnvironmentData environmentData,
            @NonNull LDLogger logger
    ) {
        this.environmentData = environmentData;
        this.logger = logger;
    }

    @Override
    public FlagStore createFlagStore(@NonNull String hashedContextId) {
        return new FlagStoreImpl(environmentData, hashedContextId, logger);
    }
}
