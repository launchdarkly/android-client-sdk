package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.fdv2.Selector;

/**
 * Provides the current {@link Selector} for an FDv2 data store.
 * <p>
 * FDv2 streaming and polling implementations use this to read the selector so they can send it
 * as the {@code basis} query parameter on each request, allowing the server to return an
 * incremental changeset rather than a full payload.
 * <p>
 * The selector is stored in memory only and is not persisted across process restarts.
 * Immediately after startup or a context switch the selector will be {@link Selector#EMPTY}.
 * <p>
 * Analogous to {@code SelectorSource} in the java-server SDK (java-core). Kept separate from
 * the update-sink interface so that selector reads and flag writes remain independent concerns.
 */
public interface SelectorSource {

    /**
     * Returns the current selector. Never null; may be {@link Selector#EMPTY}.
     *
     * @return the current selector
     */
    @NonNull
    Selector getSelector();
}
