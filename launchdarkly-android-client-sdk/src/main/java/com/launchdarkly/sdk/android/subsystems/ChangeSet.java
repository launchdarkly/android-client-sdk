package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.DataModel;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a set of changes to apply to the flag store.
 * <p>
 * A changeset has a type ({@link ChangeSetType#Full}, {@link ChangeSetType#Partial}, or
 * {@link ChangeSetType#None}), an optional {@link Selector}
 * to store in memory for the next request (e.g. as basis), and for Full/Partial types a map of
 * flag key to flag data.
 */
public final class ChangeSet {
    private final ChangeSetType type;
    private final Selector selector;
    @Nullable
    private final String environmentId;
    private final Map<String, DataModel.Flag> items;
    private final boolean shouldPersist;

    /**
     * Creates a changeset.
     *
     * @param type           the type of the changeset
     * @param selector       the selector for this change (may be {@link Selector#EMPTY})
     * @param items          map of flag key to flag (empty for None; used for Full and Partial)
     * @param environmentId  optional environment identifier, or null
     * @param shouldPersist  true if the data should be persisted
     */
    public ChangeSet(
            @NonNull ChangeSetType type,
            @NonNull Selector selector,
            @NonNull Map<String, DataModel.Flag> items,
            @Nullable String environmentId,
            boolean shouldPersist
    ) {
        this.type = type;
        this.selector = selector != null ? selector : Selector.EMPTY;
        this.environmentId = environmentId;
        this.items = items != null ? Collections.unmodifiableMap(items) : Collections.emptyMap();
        this.shouldPersist = shouldPersist;
    }

    /**
     * Returns the type of the changeset.
     *
     * @return the changeset type
     */
    @NonNull
    public ChangeSetType getType() {
        return type;
    }

    /**
     * Returns the selector for this change. Will not be null; may be {@link Selector#EMPTY}.
     *
     * @return the selector
     */
    @NonNull
    public Selector getSelector() {
        return selector;
    }

    /**
     * Returns the environment ID associated with the change, or null if not available.
     *
     * @return the environment ID, or null if not available
     */
    @Nullable
    public String getEnvironmentId() {
        return environmentId;
    }

    /**
     * Returns the flag items in this changeset. For Full and Partial types, map of flag key to flag;
     * for None, empty. The returned map is unmodifiable.
     *
     * @return the flag items; may be empty but will not be null
     */
    @NonNull
    public Map<String, DataModel.Flag> getItems() {
        return items;
    }

    /**
     * Returns whether this data should be persisted to persistent stores.
     *
     * @return true if the data should be persisted, false otherwise
     */
    public boolean shouldPersist() {
        return shouldPersist;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ChangeSet that = (ChangeSet) o;
        return type == that.type
                && shouldPersist == that.shouldPersist
                && Objects.equals(selector, that.selector)
                && Objects.equals(environmentId, that.environmentId)
                && Objects.equals(items, that.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, selector, environmentId, items, shouldPersist);
    }

    @Override
    public String toString() {
        return "ChangeSet(" + type + "," + selector + "," + environmentId + "," + items + "," + shouldPersist + ")";
    }
}
