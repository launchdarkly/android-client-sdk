package com.launchdarkly.android.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.launchdarkly.android.EvaluationReason;
import com.launchdarkly.android.response.FlagsResponse;

public class GsonCache {

    private static final Gson gson = createGson();

    public static Gson getGson() {
        return gson;
    }

    private static Gson createGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(EvaluationReason.class, new EvaluationReasonSerialization());
        gsonBuilder.registerTypeAdapter(FlagsResponse.class, new FlagsResponseSerialization());
        return gsonBuilder.create();
    }
}
