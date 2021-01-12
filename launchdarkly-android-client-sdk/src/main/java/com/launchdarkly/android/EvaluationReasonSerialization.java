package com.launchdarkly.android;

import androidx.annotation.Nullable;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;

class EvaluationReasonSerialization implements JsonSerializer<EvaluationReason>, JsonDeserializer<EvaluationReason> {

    @Nullable
    private static <T extends Enum<T>> T parseEnum(Class<T> c, String name, T fallback) {
        try {
            return Enum.valueOf(c, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    public JsonElement serialize(EvaluationReason src, Type typeOfSrc, JsonSerializationContext context) {
        return context.serialize(src, src.getClass());
    }

    @Override
    public EvaluationReason deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject o = json.getAsJsonObject();
        if (o == null) {
            return null;
        }
        JsonElement kindElement = o.get("kind");
        if (kindElement != null && kindElement.isJsonPrimitive() && kindElement.getAsJsonPrimitive().isString()) {
            EvaluationReason.Kind kind = parseEnum(EvaluationReason.Kind.class, kindElement.getAsString(), EvaluationReason.Kind.UNKNOWN);
            if (kind == null) {
                return null;
            }
            switch (kind) {
                case OFF:
                    return EvaluationReason.off();
                case FALLTHROUGH:
                    return EvaluationReason.fallthrough();
                case TARGET_MATCH:
                    return EvaluationReason.targetMatch();
                case RULE_MATCH:
                    JsonElement indexElement = o.get("ruleIndex");
                    JsonElement idElement = o.get("ruleId");
                    if (indexElement != null && indexElement.isJsonPrimitive() && indexElement.getAsJsonPrimitive().isNumber() &&
                            idElement != null && idElement.isJsonPrimitive() && idElement.getAsJsonPrimitive().isString()) {
                        return EvaluationReason.ruleMatch(indexElement.getAsInt(),
                                idElement.getAsString());
                    }
                    return null;
                case PREREQUISITE_FAILED:
                    JsonElement prereqElement = o.get("prerequisiteKey");
                    if (prereqElement != null && prereqElement.isJsonPrimitive() && prereqElement.getAsJsonPrimitive().isString()) {
                        return EvaluationReason.prerequisiteFailed(prereqElement.getAsString());
                    }
                    break;
                case ERROR:
                    JsonElement errorKindElement = o.get("errorKind");
                    if (errorKindElement != null && errorKindElement.isJsonPrimitive() && errorKindElement.getAsJsonPrimitive().isString()) {
                        EvaluationReason.ErrorKind errorKind = parseEnum(EvaluationReason.ErrorKind.class, errorKindElement.getAsString(), EvaluationReason.ErrorKind.UNKNOWN);
                        return EvaluationReason.error(errorKind);
                    }
                    return null;
                case UNKNOWN:
                    return EvaluationReason.unknown();
            }
        }
        return null;
    }


}
