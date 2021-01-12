package com.launchdarkly.android;

import android.app.Application;
import androidx.annotation.NonNull;

class SharedPrefsFlagStoreFactory implements FlagStoreFactory {

    private final Application application;

    SharedPrefsFlagStoreFactory(@NonNull Application application) {
        this.application = application;
    }

    @Override
    public FlagStore createFlagStore(@NonNull String identifier) {
        return new SharedPrefsFlagStore(application, identifier);
    }
}
