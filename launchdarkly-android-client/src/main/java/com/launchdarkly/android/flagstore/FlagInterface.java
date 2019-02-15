package com.launchdarkly.android.flagstore;

import android.support.annotation.NonNull;

import com.google.gson.JsonElement;
import com.launchdarkly.android.EvaluationReason;

public interface FlagInterface {

    @NonNull
    String getKey();

    JsonElement getValue();

    Integer getVersion();

    Integer getFlagVersion();

    Integer getVariation();

    EvaluationReason getReason();
}
