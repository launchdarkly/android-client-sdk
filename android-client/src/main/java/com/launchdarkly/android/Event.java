package com.launchdarkly.android;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

class Event {
    Long creationDate;
    String key;
    String kind;
    LDUser user;

    Event(String kind, String key, LDUser user) {
        this.creationDate = System.currentTimeMillis();
        this.key = key;
        this.kind = kind;
        this.user = user;
    }
}

class IdentifyEvent extends Event {

    IdentifyEvent(LDUser user) {
        super("identify", user.getKeyAsString(), user);
    }
}

class CustomEvent extends Event {
    private final JsonElement data;

    CustomEvent(String key, LDUser user, JsonElement data) {
        super("custom", key, user);
        this.data = data;
    }
}

class FeatureRequestEvent extends Event {
    JsonElement value;
    @SerializedName("default")
    JsonElement defaultVal;


    FeatureRequestEvent(String key, LDUser user, JsonElement value, JsonElement defaultVal) {
        super("feature", key, user);
        this.value = value;
        this.defaultVal = defaultVal;
    }
}
