package com.launchdarkly.android.flagstore;

/**
 * Types of updates that a FlagStore can report
 */
public enum FlagStoreUpdateType {
    /**
     * The flag was deleted
     */
    FLAG_DELETED,
    /**
     * The flag has been updated or replaced
     */
    FLAG_UPDATED,
    /**
     * A new flag has been created
     */
    FLAG_CREATED
}
