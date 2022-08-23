package com.launchdarkly.sdk.android;

class DeleteFlagResponse implements FlagUpdate {

    private final String key;
    private final int version;

    DeleteFlagResponse(String key, int version) {
        this.key = key;
        this.version = version;
    }

    /**
     * Returns an updated version of the flag that is in a deleted state, if the update is valid
     * (has a higher version than any existing version in the store), otherwise returns the
     * existing flag.
     *
     * @param before An existing Flag associated with flagKey from flagToUpdate()
     * @return the new Flag state
     */
    @Override
    public Flag updateFlag(Flag before) {
        if (before == null || this.version > before.getVersion()) {
            return Flag.deletedItemPlaceholder(key, version);
        }
        return before;
    }

    @Override
    public String flagToUpdate() {
        return key;
    }

    public int getVersion() {
        return version;
    }
}
