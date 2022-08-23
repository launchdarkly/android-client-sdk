package com.launchdarkly.sdk.android;

import android.util.Pair;

import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

import org.easymock.EasyMockSupport;
import org.easymock.IArgumentMatcher;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.reportMatcher;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public abstract class FlagStoreTest extends EasyMockSupport {

    public abstract FlagStore createFlagStore(String identifier);

    protected static class FlagMatcher implements IArgumentMatcher {

        private final Flag flag;

        FlagMatcher(Flag flag) {
            this.flag = flag;
        }

        @Override
        public boolean matches(Object argument) {
            if (argument == flag) {
                return true;
            }
            if (argument instanceof Flag) {
                Flag received = (Flag) argument;
                return Objects.equals(flag.getKey(), received.getKey()) &&
                        Objects.equals(flag.getValue(), received.getValue()) &&
                        Objects.equals(flag.getVersion(), received.getVersion()) &&
                        Objects.equals(flag.getFlagVersion(), received.getFlagVersion()) &&
                        Objects.equals(flag.getVariation(), received.getVariation()) &&
                        Objects.equals(flag.isTrackEvents(), received.isTrackEvents()) &&
                        Objects.equals(flag.isTrackReason(), received.isTrackReason()) &&
                        Objects.equals(flag.getDebugEventsUntilDate(),
                                received.getDebugEventsUntilDate()) &&
                        Objects.equals(flag.getReason(), received.getReason());
            }
            return false;
        }

        @Override
        public void appendTo(StringBuffer buffer) {
            if (flag == null) {
                buffer.append("null");
            } else {
                buffer.append("Flag(\"");
                buffer.append(flag.getKey());
                buffer.append("\")");
            }
        }
    }


    private void assertExpectedFlag(Flag expected, Flag received) {
        assertEquals(expected.getKey(), received.getKey());
        assertEquals(expected.getValue(), received.getValue());
        assertEquals(expected.getVersion(), received.getVersion());
        assertEquals(expected.getFlagVersion(), received.getFlagVersion());
        assertEquals(expected.getVariation(), received.getVariation());
        assertEquals(expected.isTrackEvents(), received.isTrackEvents());
        assertEquals(expected.isTrackReason(), received.isTrackReason());
        assertEquals(expected.getDebugEventsUntilDate(), received.getDebugEventsUntilDate());
        assertEquals(expected.getReason(), received.getReason());
    }

    private List<Flag> makeTestFlags() {
        // This test assumes that if the store correctly serializes and deserializes one kind of
        // EvaluationReason, it can handle any kind, since the actual marshaling is being done by
        // UserFlagResponse. Therefore, the other variants of EvaluationReason are tested by
        // FlagTest.
        final EvaluationReason reason = EvaluationReason.ruleMatch(1, "id");
        final LDValue objectVal = new ObjectBuilder()
                .put("bool", true)
                .put("num", 3.4)
                .put("string", "string")
                .put("array", new ArrayBuilder().build())
                .put("obj", new ObjectBuilder().build())
                .build();
        final Flag testFlag1 = new FlagBuilder("testFlag1").build();
        final Flag testFlag2 = new FlagBuilder("testFlag2")
                .value(new ArrayBuilder().build())
                .version(2)
                .debugEventsUntilDate(123456789L)
                .trackEvents(true)
                .trackReason(true)
                .build();
        final Flag testFlag3 = new Flag("testFlag3", objectVal, 250, 102, 3,
                false, false, 2500000000L, reason);
        final Flag testFlag4 = new FlagBuilder("_flag-with-very-long-key-name-as-well-as-period.-and-underscore_.")
                .value(LDValue.of("String value"))
                .flagVersion(4)
                .build();
        return Arrays.asList(testFlag1, testFlag2, testFlag3, testFlag4);
    }

    private static Flag eqFlag(Flag in) {
        reportMatcher(new FlagMatcher(in));
        return null;
    }

    @Test
    public void mockFlagCreateBehavior() {
        final Flag initialFlag = new FlagBuilder("flag").build();

        final FlagUpdate mockCreate = strictMock(FlagUpdate.class);
        expect(mockCreate.flagToUpdate()).andReturn("flag");
        expect(mockCreate.updateFlag(eqFlag(null))).andReturn(initialFlag);

        final StoreUpdatedListener mockUpdateListener = strictMock(StoreUpdatedListener.class);
        Pair<String, FlagStoreUpdateType> update = new Pair<>("flag", FlagStoreUpdateType.FLAG_CREATED);
        mockUpdateListener.onStoreUpdate(Collections.singletonList(update));

        replayAll();

        final FlagStore underTest = createFlagStore("abc");
        underTest.registerOnStoreUpdatedListener(mockUpdateListener);
        underTest.applyFlagUpdate(mockCreate);

        verifyAll();

        assertEquals(1, underTest.getAllFlags().size());
        final Flag retrieved = underTest.getFlag("flag");
        assertNotNull(retrieved);
        assertExpectedFlag(initialFlag, retrieved);
        assertTrue(underTest.containsKey("flag"));
    }

    @Test
    public void mockFlagUpdateBehavior() {
        final Flag initialFlag = new FlagBuilder("flag").build();
        final FlagStore underTest = createFlagStore("abc");
        underTest.applyFlagUpdate(initialFlag);

        final Flag updatedFlag = new FlagBuilder("flag").variation(5).build();
        final FlagUpdate mockUpdate = strictMock(FlagUpdate.class);
        expect(mockUpdate.flagToUpdate()).andReturn("flag");
        expect(mockUpdate.updateFlag(eqFlag(initialFlag))).andReturn(updatedFlag);

        final StoreUpdatedListener mockUpdateListener = strictMock(StoreUpdatedListener.class);
        Pair<String, FlagStoreUpdateType> update = new Pair<>("flag", FlagStoreUpdateType.FLAG_UPDATED);
        mockUpdateListener.onStoreUpdate(Collections.singletonList(update));

        replayAll();

        underTest.registerOnStoreUpdatedListener(mockUpdateListener);
        underTest.applyFlagUpdate(mockUpdate);

        verifyAll();

        assertEquals(1, underTest.getAllFlags().size());
        final Flag retrieved = underTest.getFlag("flag");
        assertNotNull(retrieved);
        assertExpectedFlag(updatedFlag, retrieved);
        assertTrue(underTest.containsKey("flag"));
    }

    @Test
    public void mockFlagDeleteBehavior() {
        final Flag initialFlag = new FlagBuilder("flag").build();
        final FlagStore underTest = createFlagStore("abc");
        underTest.applyFlagUpdate(initialFlag);

        final FlagUpdate mockDelete = strictMock(FlagUpdate.class);
        expect(mockDelete.flagToUpdate()).andReturn("flag");
        expect(mockDelete.updateFlag(eqFlag(initialFlag))).andReturn(null);

        final StoreUpdatedListener mockUpdateListener = strictMock(StoreUpdatedListener.class);
        Pair<String, FlagStoreUpdateType> update = new Pair<>("flag", FlagStoreUpdateType.FLAG_DELETED);
        mockUpdateListener.onStoreUpdate(Collections.singletonList(update));

        replayAll();

        underTest.registerOnStoreUpdatedListener(mockUpdateListener);
        underTest.applyFlagUpdate(mockDelete);

        verifyAll();

        assertNull(underTest.getFlag("flag"));
        assertEquals(0, underTest.getAllFlags().size());
        assertFalse(underTest.containsKey("flag"));
    }

    @Test
    public void testUnregisterStoreUpdate() {
        final Flag initialFlag = new FlagBuilder("flag").version(10).build();

        final FlagUpdate mockCreate = strictMock(FlagUpdate.class);
        expect(mockCreate.flagToUpdate()).andReturn("flag");
        expect(mockCreate.updateFlag(eqFlag(null))).andReturn(initialFlag);

        final StoreUpdatedListener mockUpdateListener = strictMock(StoreUpdatedListener.class);

        replayAll();

        final FlagStore underTest = createFlagStore("abc");
        underTest.registerOnStoreUpdatedListener(mockUpdateListener);
        underTest.unregisterOnStoreUpdatedListener();
        underTest.applyFlagUpdate(mockCreate);

        // Verifies mockUpdateListener doesn't get a call
        verifyAll();
    }

    @Test
    public void savesAndRetrievesFlags() {
        final List<Flag> testFlags = makeTestFlags();
        FlagStore flagStore = createFlagStore("abc");

        for (Flag flag : testFlags) {
            flagStore.applyFlagUpdate(flag);
            final Flag retrieved = flagStore.getFlag(flag.getKey());
            assertNotNull(retrieved);
            assertExpectedFlag(flag, retrieved);
        }

        // Get a new instance of FlagStore to test persistence (as best we can)
        flagStore = createFlagStore("abc");
        for (Flag flag : testFlags) {
            flagStore.applyFlagUpdate(flag);
            final Flag retrieved = flagStore.getFlag(flag.getKey());
            assertNotNull(retrieved);
            assertExpectedFlag(flag, retrieved);
        }
    }

    @Test
    public void testGetAllFlags() {
        final List<Flag> testFlags = makeTestFlags();
        final FlagStore flagStore = createFlagStore("abc");

        for (Flag flag : testFlags) {
            flagStore.applyFlagUpdate(flag);
        }

        final Collection<Flag> allFlags = flagStore.getAllFlags();
        assertEquals(testFlags.size(), flagStore.getAllFlags().size());
        int matchCount = 0;
        for (Flag flag : testFlags) {
            for (Flag retrieved : allFlags) {
                if (flag.getKey().equals(retrieved.getKey())) {
                    matchCount += 1;
                    assertExpectedFlag(flag, retrieved);
                }
            }
        }
        assertEquals(matchCount, testFlags.size());
    }

    @Test
    public void testContainsKey() {
        final List<Flag> testFlags = makeTestFlags();
        final FlagStore flagStore = createFlagStore("abc");
        for (Flag flag : testFlags) {
            assertFalse(flagStore.containsKey(flag.getKey()));
            flagStore.applyFlagUpdate(flag);
            assertTrue(flagStore.containsKey(flag.getKey()));
        }
    }

    @Test
    public void testApplyFlagUpdates() {
        final List<Flag> testFlags = makeTestFlags();
        FlagStore flagStore = createFlagStore("abc");

        flagStore.applyFlagUpdates(testFlags);

        // Get a new instance of FlagStore to test persistence (as best we can)
        flagStore = createFlagStore("abc");
        assertEquals(testFlags.size(), flagStore.getAllFlags().size());
        for (Flag flag : testFlags) {
            flagStore.applyFlagUpdate(flag);
            final Flag retrieved = flagStore.getFlag(flag.getKey());
            assertNotNull(retrieved);
            assertExpectedFlag(flag, retrieved);
        }
    }

    @Test
    public void testClear() {
        final List<Flag> testFlags = makeTestFlags();
        FlagStore flagStore = createFlagStore("abc");

        flagStore.applyFlagUpdates(testFlags);
        flagStore.clear();

        assertEquals(0, flagStore.getAllFlags().size());
        for (Flag flag : testFlags) {
            final Flag retrieved = flagStore.getFlag(flag.getKey());
            assertNull(retrieved);
        }

        // Get a new instance of FlagStore to test persistence (as best we can)
        flagStore = createFlagStore("abc");
        assertEquals(0, flagStore.getAllFlags().size());
        for (Flag flag : testFlags) {
            final Flag retrieved = flagStore.getFlag(flag.getKey());
            assertNull(retrieved);
        }
    }

    @Test
    public void testClearAndApplyFlagUpdates() {
        final List<Flag> testFlags = makeTestFlags();
        final Flag initialFlag = new FlagBuilder("flag").build();
        FlagStore flagStore = createFlagStore("abc");

        flagStore.applyFlagUpdate(initialFlag);
        flagStore.clearAndApplyFlagUpdates(testFlags);

        flagStore = createFlagStore("abc");
        assertNull(flagStore.getFlag("flag"));
        assertFalse(flagStore.containsKey("flag"));
        assertEquals(testFlags.size(), flagStore.getAllFlags().size());
        for (Flag flag : testFlags) {
            flagStore.applyFlagUpdate(flag);
            final Flag retrieved = flagStore.getFlag(flag.getKey());
            assertNotNull(retrieved);
            assertExpectedFlag(flag, retrieved);
        }
    }

    @Test
    public void testClearAndApplyOnNoFlagUpdates() {
        AtomicBoolean changed = new AtomicBoolean(false);
        List<Flag> testFlags = makeTestFlags();

        FlagStore flagStore = createFlagStore("abc");
        flagStore.clearAndApplyFlagUpdates(testFlags);

        flagStore.registerOnStoreUpdatedListener(updates -> {
            assertEquals(updates.toString(), 0, updates.size());
            changed.set(true);
        });

        flagStore.clearAndApplyFlagUpdates(testFlags);

        assertTrue(changed.get());
    }

    @Test
    public void testClearAndApplyOnChangeFlagUpdates() {
        AtomicBoolean changed = new AtomicBoolean(false);
        ArrayList<Flag> testFlags = new ArrayList<>(makeTestFlags());

        FlagStore flagStore = createFlagStore("abc");
        flagStore.clearAndApplyFlagUpdates(testFlags);

        flagStore.registerOnStoreUpdatedListener(updates -> {
            assertEquals(updates.toString(), 1, updates.size());
            changed.set(true);
        });

        testFlags.add(new FlagBuilder("yes").flagVersion(20).build());

        flagStore.clearAndApplyFlagUpdates(testFlags);

        assertTrue(changed.get());
    }
}
