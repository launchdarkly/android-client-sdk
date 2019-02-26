package com.launchdarkly.android.response;

import android.support.annotation.NonNull;

import com.launchdarkly.android.flagstore.Flag;

import java.util.List;

public class FlagsResponse {
    @NonNull
    private List<Flag> flags;

    public FlagsResponse(@NonNull List<Flag> flags) {
        this.flags = flags;
    }

    @NonNull
    public List<Flag> getFlags() {
        return flags;
    }
}
