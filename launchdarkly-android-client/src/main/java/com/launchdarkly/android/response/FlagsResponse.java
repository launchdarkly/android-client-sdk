package com.launchdarkly.android.response;

import android.support.annotation.NonNull;

import com.launchdarkly.android.flagstore.Flag;

import java.util.List;

/**
 * Used for cases where the server sends a collection of flags as a key-value object. Uses custom
 * deserializer in {@link com.launchdarkly.android.gson.FlagsResponseSerialization} to get a list of
 * {@link com.launchdarkly.android.flagstore.Flag} objects.
 */
public class FlagsResponse {
    @NonNull
    private List<Flag> flags;

    public FlagsResponse(@NonNull List<Flag> flags) {
        this.flags = flags;
    }

    /**
     * Get a list of the {@link Flag}s in this response
     *
     * @return A list of the {@link Flag}s in this response
     */
    @NonNull
    public List<Flag> getFlags() {
        return flags;
    }
}
