package com.launchdarkly.sdk.android.integrations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Lives in the {@code integrations} package to reach the constructor that takes the bound on how many
 * flags are tracked, which the SDK sets for itself rather than exposing.
 */

public class EvaluationExposureDeduperTest {
    /**
     * An exposure key that differs from every other one this test builds only by its flag key, so
     * that a test can talk about "the exposure of a" without spelling out the whole key.
     */
    private static EvaluationExposureKey key(String flagKey) {
        return new EvaluationExposureKey("default", flagKey, 1, 2, false, "user-key");
    }

    /**
     * The same flag as {@link #key(String)}, resolved to a different variation.
     */
    private static EvaluationExposureKey otherResult(String flagKey) {
        return new EvaluationExposureKey("default", flagKey, 3, 2, false, "user-key");
    }

    @Test
    public void recordsEverythingForNonPositiveWindow() {
        for (int window : new int[] { 0, -1 }) {
            EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(window, 10);
            assertTrue(deduper.shouldRecord(key("a"), 0));
            assertTrue(deduper.shouldRecord(key("a"), 0));
        }
    }

    @Test
    public void disabledRecordsEverythingAndIsSharedAcrossHooks() {
        EvaluationExposureDeduper deduper = EvaluationExposureDeduper.disabled();
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        deduper.reset();
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        // The runner recognizes it by identity to skip building exposure keys altogether.
        assertSame(deduper, EvaluationExposureDeduper.disabled());
    }

    @Test
    public void exposureKeyDistinguishesEveryComponent() {
        EvaluationExposureKey key = new EvaluationExposureKey("default", "flag", 1, 2, false, "user-key");
        EvaluationExposureKey same = new EvaluationExposureKey("default", "flag", 1, 2, false, "user-key");
        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());

        assertNotEquals(key, new EvaluationExposureKey("default", "other-flag", 1, 2, false, "user-key"));
        assertNotEquals(key, new EvaluationExposureKey("default", "flag", 3, 2, false, "user-key"));
        assertNotEquals(key, new EvaluationExposureKey("default", "flag", 1, 4, false, "user-key"));
        assertNotEquals(key, new EvaluationExposureKey("default", "flag", 1, 2, false, "other-user-key"));
        // Moving into an experiment on the same variation of the same flag version reports again.
        assertNotEquals(key, new EvaluationExposureKey("default", "flag", 1, 2, true, "user-key"));
        // A hook shared across environments observes the same result once per environment.
        assertNotEquals(key, new EvaluationExposureKey("other-env", "flag", 1, 2, false, "user-key"));
    }

    @Test
    public void suppressesRepeatsWithinWindow() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertFalse(deduper.shouldRecord(key("a"), 1000));
        assertFalse(deduper.shouldRecord(key("a"), 1099));
    }

    @Test
    public void recordsAgainOnceWindowElapses() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertTrue(deduper.shouldRecord(key("a"), 1100));
        // Recording restarts the window rather than extending the original one.
        assertFalse(deduper.shouldRecord(key("a"), 1150));
        assertTrue(deduper.shouldRecord(key("a"), 1200));
    }

    @Test
    public void tracksFlagsIndependently() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertTrue(deduper.shouldRecord(key("b"), 1000));
        assertFalse(deduper.shouldRecord(key("a"), 1000));
        assertFalse(deduper.shouldRecord(key("b"), 1000));
    }

    @Test
    public void reportsAFlagAgainAsSoonAsItsResultChanges() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertTrue(deduper.shouldRecord(otherResult("a"), 1010));
        assertFalse(deduper.shouldRecord(otherResult("a"), 1020));
        // Only the result the flag reported last is tracked, so flipping back is a change too and the
        // hook is told about it rather than being left to think the flag never returned to it.
        assertTrue(deduper.shouldRecord(key("a"), 1030));
        assertFalse(deduper.shouldRecord(key("a"), 1040));
    }

    @Test
    public void tracksTheSameFlagSeparatelyPerEnvironment() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 10);
        EvaluationExposureKey primary = new EvaluationExposureKey("default", "flag", 1, 2, false, "user-key");
        EvaluationExposureKey secondary = new EvaluationExposureKey("other", "flag", 3, 4, false, "user-key");

        // A hook set on the configuration is shared by the clients for every environment, so its
        // deduper sees both. Neither environment may look to the other like its result changing.
        assertTrue(deduper.shouldRecord(primary, 1000));
        assertTrue(deduper.shouldRecord(secondary, 1000));
        assertFalse(deduper.shouldRecord(primary, 1010));
        assertFalse(deduper.shouldRecord(secondary, 1010));
    }

    @Test
    public void recordsAgainAfterReset() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        deduper.reset();
        assertTrue(deduper.shouldRecord(key("a"), 1000));
    }

    @Test
    public void evictsTheFlagRecordedLongestAgoPastCap() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(10_000, 4);
        for (int i = 0; i < 5; i++) {
            assertTrue(deduper.shouldRecord(key("key-" + i), 1000 + i));
        }
        // "key-0" was recorded first, so it is the one dropped and can be recorded again, while the
        // most recently recorded flag is still being tracked.
        assertTrue(deduper.shouldRecord(key("key-0"), 1010));
        assertFalse(deduper.shouldRecord(key("key-4"), 1010));
    }

    @Test
    public void reRecordingMovesAFlagToMostRecentEndOfEvictionOrder() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 2);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertTrue(deduper.shouldRecord(key("b"), 1000));
        // "a" is re-recorded once its window elapses, which makes "b" the oldest tracked flag.
        assertTrue(deduper.shouldRecord(key("a"), 1100));
        assertTrue(deduper.shouldRecord(key("c"), 1100));
        assertFalse(deduper.shouldRecord(key("a"), 1100));
    }

    @Test
    public void evictionPrefersFlagsWhoseWindowHasElapsed() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 8);
        for (int i = 0; i < 2; i++) {
            assertTrue(deduper.shouldRecord(key("expired-" + i), 1000));
        }
        // The 7th of these takes the map past the cap. The flags recorded longest ago are the two
        // whose window has since elapsed, so those are the ones evicted and every live flag is still
        // tracked.
        for (int i = 0; i < 7; i++) {
            assertTrue(deduper.shouldRecord(key("live-" + i), 1150));
        }
        for (int i = 0; i < 7; i++) {
            assertFalse(deduper.shouldRecord(key("live-" + i), 1150));
        }
    }

    @Test
    public void usesTheDefaultWindowWhenBuiltWithoutOne() {
        // Ten minutes.
        assertEquals(600_000, EvaluationExposureDeduper.DEFAULT_WINDOW_MILLIS);

        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper();
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertFalse(deduper.shouldRecord(key("a"), 600_999));
        assertTrue(deduper.shouldRecord(key("a"), 601_000));
    }

    @Test
    public void boundsHowManyFlagsItTracks() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(600_000);
        for (int i = 0; i < 2_000; i++) {
            assertTrue(deduper.shouldRecord(key("key-" + i), 1000));
        }

        // Nothing about the bound is configurable, because tracking one result per flag already keeps
        // the cache to the size of the flag set. It is only reached by an application that generates
        // flag keys, and then it evicts the flag recorded longest ago.
        assertFalse(deduper.shouldRecord(key("key-1999"), 1000));
        assertTrue(deduper.shouldRecord(key("key-2000"), 1000));
        assertTrue(deduper.shouldRecord(key("key-0"), 1000));
    }

    @Test
    public void recordsOnceWhenSameKeyIsCheckedConcurrently() throws Exception {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(60_000, 100);
        EvaluationExposureKey key = key("a");
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        boolean[] recorded = new boolean[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> recorded[index] = deduper.shouldRecord(key, 1000));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        int recordedCount = 0;
        for (boolean value : recorded) {
            if (value) {
                recordedCount++;
            }
        }
        assertEquals(1, recordedCount);
    }
}
