package com.launchdarkly.android.response;

import com.google.gson.JsonElement;

/**
 * Farhan
 * 2018-01-30
 */
public interface FlagResponse {

    String getKey();

    JsonElement getValue();

    float getVersion();
}
