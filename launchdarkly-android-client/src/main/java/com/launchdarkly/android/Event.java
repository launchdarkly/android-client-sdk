package com.launchdarkly.android;

import com.google.gson.JsonElement;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

class Event {
    @Expose
    Long creationDate;
    @Expose
    String key;
    @Expose
    String kind;
    @Expose
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
    @Expose
    private final JsonElement data;

    CustomEvent(String key, LDUser user, JsonElement data) {
        super("custom", key, user);
        this.data = data;
    }
}

class FeatureRequestEvent extends Event {
    @Expose
    JsonElement value;

    @Expose
    @SerializedName("default")
    JsonElement defaultVal;


    FeatureRequestEvent(String key, LDUser user, JsonElement value, JsonElement defaultVal) {
        super("feature", key, user);
        this.value = value;
        this.defaultVal = defaultVal;
    }
}
