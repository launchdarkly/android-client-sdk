package com.launchdarkly.android;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.launchdarkly.android.LDFailure;
import com.launchdarkly.android.LDInvalidResponseCodeFailure;

import java.lang.reflect.Type;

class LDFailureSerialization implements JsonSerializer<LDFailure>, JsonDeserializer<LDFailure> {
    @Override
    public LDFailure deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject in = json.getAsJsonObject();
        LDFailure.FailureType failureType = context.deserialize(in.get("failureType"), LDFailure.FailureType.class);
        String message = in.getAsJsonPrimitive("message").getAsString();
        if (failureType == LDFailure.FailureType.UNEXPECTED_RESPONSE_CODE) {
            int responseCode = in.getAsJsonPrimitive("responseCode").getAsInt();
            boolean retryable = in.getAsJsonPrimitive("retryable").getAsBoolean();
            return new LDInvalidResponseCodeFailure(message, responseCode, retryable);
        } else {
            return new LDFailure(message, failureType);
        }
    }

    @Override
    public JsonElement serialize(LDFailure src, Type typeOfSrc, JsonSerializationContext context) {
        if (src == null) {
            return null;
        }
        try {
            JsonObject jsonObject = new JsonObject();
            jsonObject.add("failureType", context.serialize(src.getFailureType()));
            jsonObject.addProperty("message", src.getMessage());
            if (src instanceof LDInvalidResponseCodeFailure) {
                LDInvalidResponseCodeFailure fail = (LDInvalidResponseCodeFailure) src;
                jsonObject.addProperty("responseCode", fail.getResponseCode());
                jsonObject.addProperty("retryable", fail.isRetryable());
            }
            return jsonObject;
        } catch (Exception unused) {
            return null;
        }
    }
}
