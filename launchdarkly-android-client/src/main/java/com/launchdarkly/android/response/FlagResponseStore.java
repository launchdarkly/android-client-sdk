package com.launchdarkly.android.response;

import android.support.annotation.Nullable;

/**
 * Farhan
 * 2018-01-30
 */
public interface FlagResponseStore<T> {

    @Nullable
    T getFlagResponse();
}
