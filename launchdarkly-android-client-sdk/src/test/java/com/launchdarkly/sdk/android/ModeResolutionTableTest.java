package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Unit tests for {@link ModeResolutionTable} and the {@link ModeResolutionTable#MOBILE} constant.
 */
public class ModeResolutionTableTest {

    // ==== MOBILE table — standard Android resolution ====

    @Test
    public void mobile_foregroundWithNetwork_resolvesToStreaming() {
        ModeState state = new ModeState(true, true);
        assertEquals(ConnectionMode.STREAMING, ModeResolutionTable.MOBILE.resolve(state));
    }

    @Test
    public void mobile_backgroundWithNetwork_resolvesToBackground() {
        ModeState state = new ModeState(false, true);
        assertEquals(ConnectionMode.BACKGROUND, ModeResolutionTable.MOBILE.resolve(state));
    }

    @Test
    public void mobile_foregroundNoNetwork_resolvesToOffline() {
        ModeState state = new ModeState(true, false);
        assertEquals(ConnectionMode.OFFLINE, ModeResolutionTable.MOBILE.resolve(state));
    }

    @Test
    public void mobile_backgroundNoNetwork_resolvesToOffline() {
        ModeState state = new ModeState(false, false);
        assertEquals(ConnectionMode.OFFLINE, ModeResolutionTable.MOBILE.resolve(state));
    }

    // ==== resolve() — first match wins ====

    @Test
    public void resolve_firstMatchWins_evenIfLaterEntryAlsoMatches() {
        ModeResolutionTable table = new ModeResolutionTable(Arrays.asList(
                new ModeResolutionEntry(state -> true, ConnectionMode.POLLING),
                new ModeResolutionEntry(state -> true, ConnectionMode.STREAMING)
        ));
        assertEquals(ConnectionMode.POLLING, table.resolve(new ModeState(true, true)));
    }

    @Test
    public void resolve_skipsNonMatchingEntries() {
        ModeResolutionTable table = new ModeResolutionTable(Arrays.asList(
                new ModeResolutionEntry(state -> false, ConnectionMode.POLLING),
                new ModeResolutionEntry(state -> true, ConnectionMode.STREAMING)
        ));
        assertEquals(ConnectionMode.STREAMING, table.resolve(new ModeState(true, true)));
    }

    @Test
    public void resolve_singleEntry() {
        ModeResolutionTable table = new ModeResolutionTable(Collections.singletonList(
                new ModeResolutionEntry(state -> true, ConnectionMode.OFFLINE)
        ));
        assertEquals(ConnectionMode.OFFLINE, table.resolve(new ModeState(false, false)));
    }

    @Test(expected = IllegalStateException.class)
    public void resolve_noMatchingEntry_throws() {
        ModeResolutionTable table = new ModeResolutionTable(Collections.singletonList(
                new ModeResolutionEntry(state -> false, ConnectionMode.OFFLINE)
        ));
        table.resolve(new ModeState(true, true));
    }

    @Test(expected = IllegalStateException.class)
    public void resolve_emptyTable_throws() {
        ModeResolutionTable table = new ModeResolutionTable(
                Collections.<ModeResolutionEntry>emptyList()
        );
        table.resolve(new ModeState(true, true));
    }

    // ==== Network takes priority over lifecycle ====

    @Test
    public void mobile_networkUnavailable_alwaysResolvesToOffline_regardlessOfForeground() {
        assertEquals(ConnectionMode.OFFLINE,
                ModeResolutionTable.MOBILE.resolve(new ModeState(true, false)));
        assertEquals(ConnectionMode.OFFLINE,
                ModeResolutionTable.MOBILE.resolve(new ModeState(false, false)));
    }
}
