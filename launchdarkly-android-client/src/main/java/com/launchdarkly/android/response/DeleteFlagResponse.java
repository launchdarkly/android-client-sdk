package com.launchdarkly.android.response;

import com.launchdarkly.android.flagstore.Flag;
import com.launchdarkly.android.flagstore.FlagUpdate;

public class DeleteFlagResponse implements FlagUpdate {

    private String key;
    private Integer version;

    public DeleteFlagResponse(String key, Integer version) {
        this.key = key;
        this.version = version;
    }

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
