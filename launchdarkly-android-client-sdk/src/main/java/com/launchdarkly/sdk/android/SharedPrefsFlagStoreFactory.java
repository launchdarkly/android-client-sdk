package com.launchdarkly.sdk.android;

import android.app.Application;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;

class SharedPrefsFlagStoreFactory implements FlagStoreFactory {

    private final Application application;
    private final LDLogger logger;

    SharedPrefsFlagStoreFactory(@NonNull Application application, LDLogger logger) {
        this.application = application;
        this.logger = logger;
    }

    @Override
    public FlagStore createFlagStore(@NonNull String identifier) {
        return new SharedPrefsFlagStore(application, identifier, logger);
    }
}
