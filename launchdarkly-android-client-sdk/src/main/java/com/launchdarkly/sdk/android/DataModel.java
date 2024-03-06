package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.json.SerializationException;

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
                @Nullable EvaluationReason reason
        ) {
            this(key, value, version, flagVersion, variation, reason, trackEvents, trackReason, debugEventsUntilDate, false);
        }

        /**
         * @param key of the flag
         * @param version of the flag
         * @return a placeholder {@link Flag} to represent a deleted flag
         */
        public static Flag deletedItemPlaceholder(@NonNull String key, int version) {
            return new Flag(key, null, version, null, null, null, false, false, null, true);
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

        EvaluationReason getReason() {
            return reason;
        }

        boolean isTrackEvents() {
            return trackEvents != null && trackEvents.booleanValue();
        }

        boolean isTrackReason() { return trackReason != null && trackReason.booleanValue(); }

        Long getDebugEventsUntilDate() {
            return debugEventsUntilDate;
        }

        int getVersionForEvents() {
            return flagVersion == null ? version : flagVersion.intValue();
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
                return gsonInstance().fromJson(json, Flag.class);
            } catch (Exception e) { // Gson throws various kinds of parsing exceptions that have no common base class
                throw new SerializationException(e);
            }
        }

        /**
         * @return JSON serialization of the flag
         */
        public String toJson() {
            return gsonInstance().toJson(this);
        }
    }
}
