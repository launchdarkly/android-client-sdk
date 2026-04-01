package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * An ordered list of {@link ModeResolutionEntry} values that maps a {@link ModeState}
 * to a {@link ConnectionMode}. The first entry whose condition matches wins.
 * If no entry matches, a default {@link ConnectionMode} is returned.
 * <p>
 * The {@link #MOBILE} constant defines the Android default resolution table
 * ({@link #createMobile(ConnectionMode, ConnectionMode)} with foreground
 * {@link ConnectionMode#STREAMING} and background {@link ConnectionMode#BACKGROUND}):
 * <ol>
 *   <li>No network → {@link ConnectionMode#OFFLINE}</li>
 *   <li>Background + background updating disabled → {@link ConnectionMode#OFFLINE}</li>
 *   <li>Background (network available, background updates allowed) → {@code backgroundMode}
 *       ({@link ConnectionMode#BACKGROUND} for {@link #MOBILE})</li>
 *   <li>Foreground with network → {@code foregroundMode}
 *       ({@link ConnectionMode#STREAMING} for {@link #MOBILE})</li>
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
     *
     * @param foregroundMode the mode to use when in the foreground with network available
     * @param backgroundMode the mode to use when in the background with network available
     *                         and background updating not disabled
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
                    state -> !state.isForeground() && state.isBackgroundUpdatingDisabled(),
                    ConnectionMode.OFFLINE),
            new ModeResolutionEntry(
                    state -> !state.isForeground(),
                    backgroundMode)
        ), foregroundMode);
    }

    private final List<ModeResolutionEntry> entries;
    private final ConnectionMode defaultMode;

    ModeResolutionTable(@NonNull List<ModeResolutionEntry> entries, @NonNull ConnectionMode defaultMode) {
        this.entries = Collections.unmodifiableList(entries);
        this.defaultMode = defaultMode;
    }

    /**
     * Evaluates the table against the given state and returns the first matching mode.
     * If no entry matches, returns the default mode.
     *
     * @param state the current platform state
     * @return the resolved {@link ConnectionMode}
     */
    @NonNull
    ConnectionMode resolve(@NonNull ModeState state) {
        for (ModeResolutionEntry entry : entries) {
            if (entry.getConditions().test(state)) {
                return entry.getMode();
            }
        }
        return defaultMode;
    }
}
