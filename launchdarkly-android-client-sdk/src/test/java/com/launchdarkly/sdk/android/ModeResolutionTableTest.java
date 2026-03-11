package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ModeResolutionTableTest {

    // ==== MOBILE table tests ====

    @Test
    public void mobile_foregroundWithNetwork_resolvesToStreaming() {
        ModeState state = new ModeState(true, true);
        assertSame(ConnectionMode.STREAMING, ModeResolutionTable.MOBILE.resolve(state));
    }

    @Test
    public void mobile_backgroundWithNetwork_resolvesToBackground() {
        ModeState state = new ModeState(false, true);
        assertSame(ConnectionMode.BACKGROUND, ModeResolutionTable.MOBILE.resolve(state));
    }

    @Test
    public void mobile_foregroundWithoutNetwork_resolvesToOffline() {
        ModeState state = new ModeState(true, false);
        assertSame(ConnectionMode.OFFLINE, ModeResolutionTable.MOBILE.resolve(state));
    }

    @Test
    public void mobile_backgroundWithoutNetwork_resolvesToOffline() {
        ModeState state = new ModeState(false, false);
        assertSame(ConnectionMode.OFFLINE, ModeResolutionTable.MOBILE.resolve(state));
    }

    // ==== Custom table tests ====

    @Test
    public void customTable_firstMatchWins() {
        ModeResolutionTable table = new ModeResolutionTable(Arrays.asList(
                new ModeResolutionEntry(state -> true, ConnectionMode.POLLING),
                new ModeResolutionEntry(state -> true, ConnectionMode.STREAMING)
        ));
        assertSame(ConnectionMode.POLLING, table.resolve(new ModeState(true, true)));
    }

    @Test(expected = IllegalStateException.class)
    public void emptyTable_throws() {
        ModeResolutionTable table = new ModeResolutionTable(Collections.<ModeResolutionEntry>emptyList());
        table.resolve(new ModeState(true, true));
    }

    @Test(expected = IllegalStateException.class)
    public void noMatch_throws() {
        ModeResolutionTable table = new ModeResolutionTable(Collections.singletonList(
                new ModeResolutionEntry(state -> false, ConnectionMode.STREAMING)
        ));
        table.resolve(new ModeState(true, true));
    }

    // ==== ModeState tests ====

    @Test
    public void modeState_getters() {
        ModeState state = new ModeState(true, false);
        assertEquals(true, state.isForeground());
        assertEquals(false, state.isNetworkAvailable());
    }

    // ==== ModeResolutionEntry tests ====

    @Test
    public void modeResolutionEntry_getters() {
        ModeResolutionEntry.Condition cond = state -> true;
        ModeResolutionEntry entry = new ModeResolutionEntry(cond, ConnectionMode.OFFLINE);
        assertSame(cond, entry.getConditions());
        assertSame(ConnectionMode.OFFLINE, entry.getMode());
    }
}
