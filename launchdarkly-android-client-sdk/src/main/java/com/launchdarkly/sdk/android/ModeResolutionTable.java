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

    static final ModeResolutionTable MOBILE = createMobile(
            ConnectionMode.STREAMING, ConnectionMode.BACKGROUND);

    /**
     * Creates a mobile resolution table with configurable foreground and background modes.
     * The resolution order is:
     * <ol>
     *   <li>No network &rarr; OFFLINE</li>
     *   <li>Background &rarr; {@code backgroundMode}</li>
     *   <li>Foreground (catch-all) &rarr; {@code foregroundMode}</li>
     * </ol>
     *
     * @param foregroundMode the mode to use when in the foreground
     * @param backgroundMode the mode to use when in the background
     * @return a new resolution table
     */
    static ModeResolutionTable createMobile(
            ConnectionMode foregroundMode,
            ConnectionMode backgroundMode
    ) {
        return new ModeResolutionTable(Arrays.asList(
                new ModeResolutionEntry(
                        state -> !state.isNetworkAvailable(),
                        ConnectionMode.OFFLINE),
                new ModeResolutionEntry(
                        state -> !state.isForeground(),
                        backgroundMode),
                new ModeResolutionEntry(
                        state -> true,
                        foregroundMode)
        ));
    }

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
