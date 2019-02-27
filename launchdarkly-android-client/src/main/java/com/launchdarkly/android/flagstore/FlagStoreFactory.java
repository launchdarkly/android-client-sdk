package com.launchdarkly.android.flagstore;

import android.support.annotation.NonNull;

public interface FlagStoreFactory {

    FlagStore createFlagStore(@NonNull String identifier);

}
