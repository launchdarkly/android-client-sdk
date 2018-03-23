package com.launchdarkly.android.response;

import android.support.annotation.NonNull;

import com.google.common.base.Function;
import com.google.gson.JsonObject;
import com.launchdarkly.android.response.interpreter.FlagResponseInterpreter;

/**
 * Farhan
 * 2018-01-30
 */
@SuppressWarnings("Guava")
public class UserFlagResponseStore<T> implements FlagResponseStore<T> {

    @NonNull
    private final JsonObject jsonObject;
    @NonNull
    private final Function<JsonObject, T> function;

    public UserFlagResponseStore(@NonNull JsonObject jsonObject, @NonNull FlagResponseInterpreter<T> function) {
        this.jsonObject = jsonObject;
        this.function = function;
    }

    @Override
    public T getFlagResponse() {
        return function.apply(jsonObject);
    }
}
