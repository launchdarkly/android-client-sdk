package com.launchdarkly.android;

import android.support.annotation.NonNull;

import com.google.gson.annotations.JsonAdapter;

import java.util.List;

@JsonAdapter(FlagsResponseSerialization.class)
class FlagsResponse {
    @NonNull
    private final List<Flag> flags;

    public FlagsResponse(@NonNull List<Flag> flags) {
        this.flags = flags;
    }

    @NonNull
    public List<Flag> getFlags() {
        return flags;
    }
}
