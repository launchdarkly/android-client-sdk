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
 * The {@link #MOBILE} constant defines the Android default resolution table:
 * <ol>
 *   <li>No network → {@link ConnectionMode#OFFLINE}</li>
 *   <li>Background + background updating disabled → {@link ConnectionMode#OFFLINE}</li>
 *   <li>Background → {@link ConnectionMode#BACKGROUND}</li>
 *   <li>Default → {@link ConnectionMode#STREAMING}</li>
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
                    state -> !state.isForeground() && state.isBackgroundUpdatingDisabled(),
                    ConnectionMode.OFFLINE),
            new ModeResolutionEntry(
                    state -> !state.isForeground(),
                    ConnectionMode.BACKGROUND)
    ), ConnectionMode.STREAMING);

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
