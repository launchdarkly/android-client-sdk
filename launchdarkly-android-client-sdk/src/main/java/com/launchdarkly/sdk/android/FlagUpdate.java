package com.launchdarkly.sdk.android;

/**
 * Interfaces for classes that are tied to a flagKey and can take an existing flag and determine
 * whether it should be updated/deleted/left the same based on its update payload.
 */
interface FlagUpdate {

    /**
     * Given an existing Flag retrieved by the flagKey returned by flagToUpdate(), updateFlag should
     * return null if the flag is to be deleted, a new Flag if the flag should be replaced by the
     * new Flag, or the before Flag if the flag should be left the same.
     *
     * @param before An existing Flag associated with flagKey from flagToUpdate()
     * @return null, a new Flag, or the before Flag.
     */
    Flag updateFlag(Flag before);

    /**
     * Get the key of the flag that this FlagUpdate is intended to update.
     *
     * @return The key of the flag to be updated.
     */
    String flagToUpdate();

}
