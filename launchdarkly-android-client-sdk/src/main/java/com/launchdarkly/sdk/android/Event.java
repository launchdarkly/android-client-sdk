package com.launchdarkly.sdk.android;

import androidx.annotation.Nullable;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;

import java.util.Map;

class Event {
    @Expose String kind;

    Event(String kind) {
        this.kind = kind;
    }

    static String userContextKind(LDUser user) {
        if (user.isAnonymous()) {
            return "anonymousUser";
        }
        return "user";
    }
}

class GenericEvent extends Event {
    @Expose long creationDate;
    @Expose String key;
    @Expose LDUser user;
    @Expose String userKey;

    GenericEvent(String kind, String key, LDUser user) {
        super(kind);
        this.creationDate = System.currentTimeMillis();
        this.key = key;
        this.user = user;
    }
}

class IdentifyEvent extends GenericEvent {
    IdentifyEvent(LDUser user) {
        super("identify", user.getKey(), user);
    }
}

class CustomEvent extends GenericEvent {
    @Expose final LDValue data;
    @Expose final Double metricValue;
    @Expose String contextKind;

    CustomEvent(String key, LDUser user, LDValue data, Double metricValue) {
        super("custom", key, null);
        this.userKey = user.getKey();
        this.data = data;
        this.metricValue = metricValue;
        if (Event.userContextKind(user).equals("anonymousUser")) {
            this.contextKind = Event.userContextKind(user);
        }
    }
}

class FeatureRequestEvent extends GenericEvent {
    @Expose LDValue value;
    @Expose @SerializedName("default") LDValue defaultVal;
    @Expose Integer version;
    @Expose Integer variation;
    @Expose EvaluationReason reason;
    @Expose String contextKind;

    FeatureRequestEvent(String key,
                        LDUser user,
                        LDValue value,
                        LDValue defaultVal,
                        @Nullable Integer version,
                        @Nullable Integer variation,
                        @Nullable EvaluationReason reason,
                        boolean inlineUser,
                        boolean debug) {
        super(debug ? "debug" : "feature", key, inlineUser || debug ? user : null);
        if (!debug) {
            if (!inlineUser) {
                this.userKey = user.getKey();
            }
            if (Event.userContextKind(user).equals("anonymousUser")) {
                this.contextKind = Event.userContextKind(user);
            }
        }
        this.value = value;
        this.defaultVal = defaultVal;
        this.reason = reason;
        this.version = version;
        if (variation != null) {
            this.variation = variation;
        }
    }
}

class SummaryEvent extends Event {
    @Expose Long startDate;
    @Expose Long endDate;
    @Expose Map<String, SummaryEventStore.FlagCounters> features;

    SummaryEvent(Long startDate, Long endDate, Map<String, SummaryEventStore.FlagCounters> features) {
        super("summary");
        this.startDate = startDate;
        this.endDate = endDate;
        this.features = features;
    }
}
