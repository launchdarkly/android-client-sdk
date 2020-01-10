package com.launchdarkly.android;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

class GsonCache {

    private static final Gson gson = createGson();

    static Gson getGson() {
        return gson;
    }

    private static Gson createGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        return gsonBuilder.create();
    }
}
