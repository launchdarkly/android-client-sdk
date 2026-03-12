package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * An ordered list of {@link ModeResolutionEntry} values that maps a {@link ModeState}
 * to a {@link ConnectionMode}. The first entry whose condition matches wins.
 * <p>
 * The {@link #MOBILE} constant defines the Android default resolution table:
 * <ol>
 *   <li>No network → {@link ConnectionMode#OFFLINE}</li>
 *   <li>Background → {@link ConnectionMode#BACKGROUND}</li>
 *   <li>Foreground → {@link ConnectionMode#STREAMING}</li>
 * </ol>
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ModeState
 * @see ModeResolutionEntry
 */
final class ModeResolutionTable {

    static final ModeResolutionTable MOBILE = new ModeResolutionTable(Arrays.asList(
            new ModeResolutionEntry(
                    state -> !state.isNetworkAvailable(),
                    ConnectionMode.OFFLINE),
            new ModeResolutionEntry(
                    state -> !state.isForeground(),
                    ConnectionMode.BACKGROUND),
            new ModeResolutionEntry(
                    state -> true,
                    ConnectionMode.STREAMING)
    ));

    private final List<ModeResolutionEntry> entries;

    ModeResolutionTable(@NonNull List<ModeResolutionEntry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    /**
     * Evaluates the table against the given state and returns the first matching mode.
     *
     * @param state the current platform state
     * @return the resolved {@link ConnectionMode}
     * @throws IllegalStateException if no entry matches (should not happen with a
     *         well-formed table that has a catch-all final entry)
     */
    @NonNull
    ConnectionMode resolve(@NonNull ModeState state) {
        for (ModeResolutionEntry entry : entries) {
            if (entry.getConditions().test(state)) {
                return entry.getMode();
            }
        }
        throw new IllegalStateException(
                "ModeResolutionTable has no matching entry for state: " +
                        "foreground=" + state.isForeground() + ", networkAvailable=" + state.isNetworkAvailable()
        );
    }
}
