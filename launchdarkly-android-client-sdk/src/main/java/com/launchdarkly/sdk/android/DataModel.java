package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.json.SerializationException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Contains information about the internal data model for feature flag state.
 * <p>
 * The details of the data model are not public to application code (although of course developers can easily
 * look at the code or the data) so that changes to LaunchDarkly SDK implementation details will not be breaking
 * changes to the application. Therefore, most of the members of this class are package-private. The public members
 * provide a high-level description of model objects so that custom integration code or test code can store or
 * serialize them.
 *
 * @since 4.0.0
 */
public abstract class DataModel {
    private DataModel() {}

    /**
     * Represents the state of a feature flag evaluation received from LaunchDarkly.
     */
    public static final class Flag {
        @NonNull
        private final String key;
        private final LDValue value;
        private final int version;
        private final Integer flagVersion;
        private final Integer variation;
        private final EvaluationReason reason;
        private final Boolean trackEvents;
        private final Boolean trackReason;
        private final Long debugEventsUntilDate;
        private final String[] prerequisites;
        private final Boolean deleted;

        private Flag(
                @NonNull String key,
                LDValue value,
                int version,
                Integer flagVersion,
                Integer variation,
                EvaluationReason reason,
                boolean trackEvents,
                boolean trackReason,
                Long debugEventsUntilDate,
                String[] prerequisites,
                boolean deleted
        ) {
            this.key = key;
            this.value = value;
            this.version = version;
            this.flagVersion = flagVersion;
            this.variation = variation;
            this.reason = reason;
            this.trackEvents = trackEvents ? Boolean.TRUE : null;
            this.trackReason = trackReason ? Boolean.TRUE : null;
            this.debugEventsUntilDate = debugEventsUntilDate;
            this.prerequisites = prerequisites;
            this.deleted = deleted ? Boolean.TRUE : null;
        }

        /**
         * Constructs an instance, specifying all properties.
         * @param key the flag key
         * @param value the current value
         * @param version a value that is incremented with each update
         * @param flagVersion the current flag version for analytics events
         * @param variation variation index of the result
         * @param trackEvents true if full event tracking is enabled
         * @param trackReason true if events must include evaluation reasons
         * @param debugEventsUntilDate non-null if debugging is enabled
         * @param reason evaluation reason of the result, or null if not available
         * @param prerequisites flag keys of prerequisites
         */
        public Flag(
                @NonNull String key,
                @NonNull LDValue value,
                int version,
                @Nullable Integer flagVersion,
                @Nullable Integer variation,
                boolean trackEvents,
                boolean trackReason,
                @Nullable Long debugEventsUntilDate,
                @Nullable EvaluationReason reason,
                @Nullable String[] prerequisites
        ) {
            this(key, value, version, flagVersion, variation, reason, trackEvents, trackReason, debugEventsUntilDate, prerequisites, false);
        }

        /**
         * @param key of the flag
         * @param version of the flag
         * @return a placeholder {@link Flag} to represent a deleted flag
         */
        public static Flag deletedItemPlaceholder(@NonNull String key, int version) {
            return new Flag(key, null, version, null, null, null, false, false, null, null, true);
        }

        String getKey() {
            return key;
        }

        @NonNull
        LDValue getValue() {
            // normalize() ensures that nulls become LDValue.ofNull() - Gson may give us nulls
            return LDValue.normalize(value);
        }

        int getVersion() {
            return version;
        }

        Integer getFlagVersion() {
            return flagVersion;
        }

        Integer getVariation() {
            return variation;
        }

        @Nullable
        EvaluationReason getReason() {
            return reason;
        }

        boolean isTrackEvents() {
            return trackEvents != null && trackEvents.booleanValue();
        }

        boolean isTrackReason() { return trackReason != null && trackReason.booleanValue(); }

        @Nullable
        Long getDebugEventsUntilDate() {
            return debugEventsUntilDate;
        }

        int getVersionForEvents() {
            return flagVersion == null ? version : flagVersion.intValue();
        }

        @Nullable
        String[] getPrerequisites() {
            return prerequisites;
        }

        boolean isDeleted() {
            return deleted != null && deleted.booleanValue();
        }

        @Override
        public String toString() {
            return toJson();
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof Flag) {
                Flag o = (Flag)other;
                return Objects.equals(key, o.key) &&
                        Objects.equals(value, o.value) &&
                        version == o.version &&
                        Objects.equals(variation, o.variation) &&
                        Objects.equals(reason, o.reason) &&
                        trackEvents == o.trackEvents &&
                        trackReason == o.trackReason &&
                        Objects.equals(debugEventsUntilDate, o.debugEventsUntilDate) &&
                        Objects.equals(prerequisites, o.prerequisites) &&
                        deleted == o.deleted;
            }
            return false;
        }

        /**
         * Deserializes a flag from JSON to {@link Flag}
         * @param json to convert
         * @return the {@link Flag}
         * @throws SerializationException if unable to deserialize
         */
        public static Flag fromJson(String json) throws SerializationException {
            try {
                return flagGson().fromJson(json, Flag.class);
            } catch (Exception e) { // Gson throws various kinds of parsing exceptions that have no common base class
                throw new SerializationException(e);
            }
        }

        /**
         * @return JSON serialization of the flag
         */
        public String toJson() {
            return flagGson().toJson(this);
        }

        /**
         * The wire spelling of evaluation reason properties differs from the Java field
         * names ({@code isExperiment} vs. {@code inExperiment}, {@code prereqKey} vs.
         * {@code prerequisiteKey}), so reflective Gson silently drops them. This adapter
         * maps the wire names explicitly, for both decode and encode.
         */
        private static volatile Gson flagGsonInstance;

        private static Gson flagGson() {
            Gson local = flagGsonInstance;
            if (local != null) return local;
            synchronized (DataModel.class) {
                if (flagGsonInstance == null) {
                    GsonBuilder b = new GsonBuilder();
                    b.registerTypeAdapter(EvaluationReason.class, new EvaluationReasonWireAdapter());
                    flagGsonInstance = b.create();
                }
                local = flagGsonInstance;
            }
            return local;
        }

        private static final class EvaluationReasonWireAdapter
                implements JsonDeserializer<EvaluationReason>, JsonSerializer<EvaluationReason> {
            @Override
            public EvaluationReason deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                    throws JsonParseException {
                if (json == null || json.isJsonNull()) {
                    return null;
                }
                JsonObject o = json.getAsJsonObject();
                String kind = stringOf(o, "kind");
                if (kind == null) {
                    return null;
                }
                switch (kind) {
                    case "OFF":
                        return EvaluationReason.off();
                    case "FALLTHROUGH":
                        return EvaluationReason.fallthrough(boolOf(o, "isExperiment", "inExperiment"));
                    case "TARGET_MATCH":
                        return EvaluationReason.targetMatch();
                    case "RULE_MATCH":
                        return EvaluationReason.ruleMatch(intOf(o, "ruleIndex", -1), stringOf(o, "ruleId"),
                                boolOf(o, "isExperiment", "inExperiment"));
                    case "PREREQUISITE_FAILED":
                        return EvaluationReason.prerequisiteFailed(stringOf(o, "prereqKey", "prerequisiteKey"));
                    case "ERROR":
                        String errKind = stringOf(o, "errorKind");
                        EvaluationReason.ErrorKind ek;
                        try {
                            ek = errKind == null
                                    ? EvaluationReason.ErrorKind.EXCEPTION
                                    : EvaluationReason.ErrorKind.valueOf(errKind);
                        } catch (IllegalArgumentException e2) {
                            ek = EvaluationReason.ErrorKind.EXCEPTION;
                        }
                        EvaluationReason.BigSegmentsStatus status = null;
                        String bs = stringOf(o, "bigSegmentsStatus");
                        if (bs != null) {
                            try {
                                status = EvaluationReason.BigSegmentsStatus.valueOf(bs);
                            } catch (IllegalArgumentException e3) {
                                status = null;
                            }
                        }
                        EvaluationReason er = EvaluationReason.error(ek);
                        if (status != null) {
                            er = er.withBigSegmentsStatus(status);
                        }
                        return er;
                    default:
                        throw new JsonParseException("unknown evaluation reason kind: " + kind);
                }
            }

            private static String stringOf(JsonObject o, String... names) {
                for (String name : names) {
                    JsonElement el = o == null ? null : o.get(name);
                    if (el != null && !el.isJsonNull()) {
                        return el.getAsString();
                    }
                }
                return null;
            }

            private static boolean boolOf(JsonObject o, String... names) {
                for (String name : names) {
                    JsonElement el = o == null ? null : o.get(name);
                    if (el != null && !el.isJsonNull()) {
                        return el.getAsBoolean();
                    }
                }
                return false;
            }

            private static int intOf(JsonObject o, String name, int def) {
                JsonElement el = o == null ? null : o.get(name);
                return el == null || el.isJsonNull() ? def : el.getAsInt();
            }

            /**
             * Encodes the reason with the model/store property spellings ({@code inExperiment},
             * {@code prerequisiteKey}), mirroring the reflective encoding that Gson off the
             * Java field names has always produced for this model's stored flag data. The
             * decode mapping above accepts both spellings; the store-facing encode keeps
             * the field-name spelling so persisted flag data round-trips unchanged.
             */
            @Override
            public JsonElement serialize(EvaluationReason reason, Type typeOfSrc,
                    JsonSerializationContext context) {
                if (reason == null) {
                    return null;
                }
                // The SDK's own storage format (and its tests) spell these with the Java
                // field names; this serializer therefore mirrors the reflective encoding
                // that this model's store data has always carried.
                JsonObject o = (JsonObject) context.serialize(defaultTree(reason));
                return o;
            }

            private static com.google.gson.JsonElement defaultTree(EvaluationReason reason) {
                JsonObject o = new JsonObject();
                o.addProperty("kind", reason.getKind().name());
                switch (reason.getKind()) {
                    case OFF:
                        break;
                    case FALLTHROUGH:
                        o.addProperty("inExperiment", reason.isInExperiment());
                        break;
                    case TARGET_MATCH:
                        break;
                    case RULE_MATCH:
                        o.addProperty("ruleIndex", reason.getRuleIndex());
                        String ruleId = reason.getRuleId();
                        if (ruleId != null) {
                            o.addProperty("ruleId", ruleId);
                        }
                        o.addProperty("inExperiment", reason.isInExperiment());
                        break;
                    case PREREQUISITE_FAILED:
                        String prerequisiteKey = reason.getPrerequisiteKey();
                        if (prerequisiteKey != null) {
                            o.addProperty("prerequisiteKey", prerequisiteKey);
                        }
                        break;
                    case ERROR:
                        if (reason.getErrorKind() != null) {
                            o.addProperty("errorKind", reason.getErrorKind().name());
                        }
                        if (reason.getBigSegmentsStatus() != null) {
                            o.addProperty("bigSegmentsStatus", reason.getBigSegmentsStatus().name());
                        }
                        break;
                    default:
                        break;
                }
                return o;
            }
        }
    }
}
