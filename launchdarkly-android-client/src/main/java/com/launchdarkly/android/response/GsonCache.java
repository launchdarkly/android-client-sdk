package com.launchdarkly.android.response;

import android.support.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.launchdarkly.android.EvaluationReason;

import java.lang.reflect.Type;

public class GsonCache {

    private static Gson gson;

    public static Gson getGson() {
        if (gson == null) {
            gson = createGson();
        }
        return gson;
    }

    @Nullable
    private static <T extends Enum<T>> T parseEnum(Class<T> c, String name, T fallback) {
        try {
            return Enum.valueOf(c, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Gson createGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        JsonSerializer<EvaluationReason> serializer = new JsonSerializer<EvaluationReason>() {
            @Override
            public JsonElement serialize(EvaluationReason src, Type typeOfSrc, JsonSerializationContext context) {
                if (src instanceof EvaluationReason.Off) {
                    return context.serialize(src, EvaluationReason.Off.class);
                } else if (src instanceof EvaluationReason.Fallthrough) {
                    return context.serialize(src, EvaluationReason.Fallthrough.class);
                } else if (src instanceof EvaluationReason.TargetMatch) {
                    return context.serialize(src, EvaluationReason.TargetMatch.class);
                } else if (src instanceof EvaluationReason.RuleMatch) {
                    return context.serialize(src, EvaluationReason.RuleMatch.class);
                } else if (src instanceof EvaluationReason.PrerequisiteFailed) {
                    return context.serialize(src, EvaluationReason.PrerequisiteFailed.class);
                } else if (src instanceof EvaluationReason.Error) {
                    return context.serialize(src, EvaluationReason.Error.class);
                } else if (src instanceof EvaluationReason.Unknown) {
                    return context.serialize(src, EvaluationReason.Unknown.class);
                }
                return null;
            }
        };
        JsonDeserializer<EvaluationReason> deserializer = new JsonDeserializer<EvaluationReason>() {
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
        };
        gsonBuilder.registerTypeAdapter(EvaluationReason.class, serializer);
        gsonBuilder.registerTypeAdapter(EvaluationReason.class, deserializer);
        return gsonBuilder.create();
    }
}
