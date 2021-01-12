package com.launchdarkly.android;

import androidx.annotation.IntRange;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.launchdarkly.android.value.LDValue;

class Event {
    @Expose String kind;

    Event(String kind) {
        this.kind = kind;
    }

    static String userContextKind(LDUser user) {
        if (user.getAnonymous() != null && user.getAnonymous()) {
            return "anonymousUser";
        }
        return "user";
    }
}

class AliasEvent extends Event {
    @Expose String key;
    @Expose String contextKind;
    @Expose String previousKey;
    @Expose String previousContextKind;
    @Expose long creationDate;

    AliasEvent(LDUser user, LDUser previousUser) {
        super("alias");
        this.key = user.getKey();
        this.contextKind = Event.userContextKind(user);
        this.previousKey = previousUser.getKey();
        this.previousContextKind = Event.userContextKind(previousUser);
        this.creationDate = System.currentTimeMillis();
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

    CustomEvent(String key, LDUser user, LDValue data, Double metricValue, boolean inlineUser) {
        super("custom", key, inlineUser ? user : null);
        if (!inlineUser) {
            this.userKey = user.getKey();
        }
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
                        @IntRange(from=(0), to=(Integer.MAX_VALUE)) int version,
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
        if (version != -1) {
            this.version = version;
        }
        if (variation != null) {
            this.variation = variation;
        }
    }
}

class SummaryEvent extends Event {
    @Expose Long startDate;
    @Expose Long endDate;
    @Expose JsonObject features;

    SummaryEvent(Long startDate, Long endDate, JsonObject features) {
        super("summary");
        this.startDate = startDate;
        this.endDate = endDate;
        this.features = features;
    }

    @Override
    public String toString() {
        JsonObject jsonObject = new JsonObject();
        if (startDate != null) {
            jsonObject.add("startDate", new JsonPrimitive(startDate));
        }
        if (endDate != null) {
            jsonObject.add("endDate", new JsonPrimitive(endDate));
        }
        if (kind != null) {
            jsonObject.add("kind", new JsonPrimitive(kind));
        }
        jsonObject.add("features", features);
        return jsonObject.toString();
    }
}
