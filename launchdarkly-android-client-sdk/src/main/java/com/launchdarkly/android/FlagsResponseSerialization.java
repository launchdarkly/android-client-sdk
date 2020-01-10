package com.launchdarkly.android;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Map;

class FlagsResponseSerialization implements JsonDeserializer<FlagsResponse> {
    @Override
    public FlagsResponse deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject o = json.getAsJsonObject();
        if (o == null) {
            return null;
        }
        ArrayList<Flag> flags = new ArrayList<>();
        for (Map.Entry<String, JsonElement> flagJson : o.entrySet()) {
            String flagKey = flagJson.getKey();
            JsonElement flagBody = flagJson.getValue();
            JsonObject flagBodyObject = flagBody.getAsJsonObject();
            if (flagBodyObject != null) {
                flagBodyObject.addProperty("key", flagKey);
            }
            Flag flag = context.deserialize(flagBodyObject, Flag.class);
            if (flag != null) {
                flags.add(flag);
            }
        }

        return new FlagsResponse(flags);
    }
}
