package com.launchdarkly.android.response;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.gson.JsonElement;

/**
 * Farhan
 * 2018-01-30
 */
public class UserFlagResponse implements FlagResponse {

    @NonNull
    private final String key;
    @Nullable
    private final JsonElement value;

    private final float version;

    public UserFlagResponse(@NonNull String key, @Nullable JsonElement value, float version) {
        this.key = key;
        this.value = value;
        this.version = version;
    }

    public UserFlagResponse(String key, JsonElement value) {
        this(key, value, Float.MIN_VALUE);
    }

    @NonNull
    @Override
    public String getKey() {
        return key;
    }

    @Nullable
    @Override
    public JsonElement getValue() {
        return value;
    }

    @Override
    public float getVersion() {
        return version;
    }
}
