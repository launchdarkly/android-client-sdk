package com.launchdarkly.android.flagstore;

import android.support.annotation.NonNull;

public interface FlagStoreFactoryInterface {

    FlagStore createFlagStore(@NonNull String identifier);

}
