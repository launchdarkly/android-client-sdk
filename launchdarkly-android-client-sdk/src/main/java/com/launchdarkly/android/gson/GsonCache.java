package com.launchdarkly.android.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.launchdarkly.android.LDFailure;
import com.launchdarkly.android.response.FlagsResponse;

@SuppressWarnings("deprecation")
@Deprecated
public class GsonCache {

    private static final Gson gson = createGson();

    public static Gson getGson() {
        return gson;
    }

    private static Gson createGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(FlagsResponse.class, new FlagsResponseSerialization());
        gsonBuilder.registerTypeAdapter(LDFailure.class, new LDFailureSerialization());
        return gsonBuilder.create();
    }
}
