package com.launchdarkly.android.response;

import com.launchdarkly.android.flagstore.Flag;
import com.launchdarkly.android.flagstore.FlagUpdate;

public class DeleteFlagResponse implements FlagUpdate {

    private final String key;
    private final Integer version;

    public DeleteFlagResponse(String key, Integer version) {
        this.key = key;
        this.version = version;
    }

    /**
     * Returns null to signal deletion of the flag if this update is valid on the supplied flag,
     * otherwise returns the existing flag.
     *
     * @param before An existing Flag associated with flagKey from flagToUpdate()
     * @return null, or the before flag.
     */
    @Override
    public Flag updateFlag(Flag before) {
        if (before == null || version == null || before.isVersionMissing() || version > before.getVersion()) {
            return null;
        }
        return before;
    }

    @Override
    public String flagToUpdate() {
        return key;
    }
}
