package com.launchdarkly.android.flagstore.sharedprefs;

import android.app.Application;
import android.support.annotation.NonNull;

import com.launchdarkly.android.flagstore.FlagStore;
import com.launchdarkly.android.flagstore.FlagStoreFactory;

@SuppressWarnings("deprecation")
@Deprecated
public class SharedPrefsFlagStoreFactory implements FlagStoreFactory {

    private final Application application;

    public SharedPrefsFlagStoreFactory(@NonNull Application application) {
        this.application = application;
    }

    @Override
    public FlagStore createFlagStore(@NonNull String identifier) {
        return new SharedPrefsFlagStore(application, identifier);
    }
}
