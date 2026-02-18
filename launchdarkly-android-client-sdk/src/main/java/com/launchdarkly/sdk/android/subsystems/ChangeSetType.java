package com.launchdarkly.sdk.android.subsystems;

/**
 * Indicates the type of a {@link ChangeSet}.
 *
 * @see ChangeSet
 */
public enum ChangeSetType {
    /**
     * Represents a full store update which replaces all flag data currently in the store.
     */
    Full,

    /**
     * Represents an incremental set of changes to be applied to the existing data in the store.
     */
    Partial,

    /**
     * Indicates that there are no flag changes; the changeset may still carry a selector to store in memory.
     */
    None
}
