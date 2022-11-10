package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.AliasEvent;
import com.launchdarkly.sdk.android.CustomEvent;
import com.launchdarkly.sdk.android.Event;
import com.launchdarkly.sdk.android.FeatureRequestEvent;
import com.launchdarkly.sdk.android.IdentifyEvent;
import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.SummaryEvent;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;

import java.lang.reflect.Type;

public class TestUtil {
    public static ClientContext simpleClientContext(LDConfig config) {
        return ClientContextImpl.fromConfig(null, config, config.getMobileKey(),
                        "", null, null, null, LDLogger.none());
    }

    private static class EventDeserializer implements JsonDeserializer<Event> {
        @Override
        public Event deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject o = json.getAsJsonObject();
            switch (o.getAsJsonPrimitive("kind").getAsString()) {
                case "summary":
                    return context.deserialize(json, SummaryEvent.class);
                case "feature":
                    return context.deserialize(json, FeatureRequestEvent.class);
                case "custom":
                    return context.deserialize(json, CustomEvent.class);
                case "identify":
                    return context.deserialize(json, IdentifyEvent.class);
                case "alias":
                    return context.deserialize(json, AliasEvent.class);
            }
            return null;
        }
    }

    public static Gson getEventDeserializerGson() {
        return new GsonBuilder()
                .registerTypeAdapter(Event.class, new EventDeserializer())
                .create();
    }

    public static void markMigrationComplete(Application application) {
        SharedPreferences migrations = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "migrations", Context.MODE_PRIVATE);
        migrations.edit().putString("v2.7.0", "v2.7.0").apply();
    }
}
