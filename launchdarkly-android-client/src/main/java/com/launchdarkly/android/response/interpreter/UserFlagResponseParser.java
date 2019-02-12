package com.launchdarkly.android.response.interpreter;

import android.support.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.EvaluationReason;
import com.launchdarkly.android.response.UserFlagResponse;

public class UserFlagResponseParser {

    public static UserFlagResponse parseFlag(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonPrimitive versionElement = getPrimitive(o, "version");
        JsonPrimitive valueElement = getPrimitive(o, "value");
        JsonPrimitive flagVersionElement = getPrimitive(o, "flagVersion");
        JsonPrimitive variationElement = getPrimitive(o, "variation");
        JsonPrimitive trackEventsElement = getPrimitive(o, "trackEvents");
        JsonPrimitive debugEventsUntilDateElement = getPrimitive(o, "debugEventsUntilDate");
        JsonElement reasonElement = o.get("reason");
        int version = versionElement != null && versionElement.isNumber()
                ? versionElement.getAsInt()
                : -1;
        Integer variation = variationElement != null && variationElement.isNumber()
                ? variationElement.getAsInt()
                : null;
        int flagVersion = flagVersionElement != null && flagVersionElement.isNumber()
                ? flagVersionElement.getAsInt()
                : -1;
        boolean trackEvents = trackEventsElement != null && trackEventsElement.isBoolean()
                && trackEventsElement.getAsBoolean();
        Long debugEventsUntilDate = debugEventsUntilDateElement != null && debugEventsUntilDateElement.isNumber()
                ? debugEventsUntilDateElement.getAsLong()
                : null;
        EvaluationReason reason = reasonElement != null && reasonElement.isJsonObject()
                ? parseReason(reasonElement.getAsJsonObject())
                :  null;
        return new UserFlagResponse(key, valueElement, version, flagVersion, variation, trackEvents, debugEventsUntilDate, reason);
    }

    @Nullable
    private static JsonPrimitive getPrimitive(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
    }

    @Nullable
    private static EvaluationReason parseReason(JsonObject o) {
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
            }
        }
        return null;
    }

    @Nullable
    private static <T extends Enum> T parseEnum(Class<T> c, String name, T fallback) {
        try {
            return Enum.valueOf(c, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
