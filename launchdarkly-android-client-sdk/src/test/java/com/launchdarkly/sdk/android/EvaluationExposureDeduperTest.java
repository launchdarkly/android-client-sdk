package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.android.integrations.EvaluationExposureDeduper;
import com.launchdarkly.sdk.android.integrations.EvaluationExposureKey;

import org.junit.Test;

public class EvaluationExposureDeduperTest {
    /**
     * An exposure key that differs from every other one this test builds only by its flag key, so
     * that a test can talk about "the exposure of a" without spelling out the whole key.
     */
    private static EvaluationExposureKey key(String flagKey) {
        return new EvaluationExposureKey("default", flagKey, 1, 2, false, "user-key");
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
    public void tracksKeysIndependently() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertTrue(deduper.shouldRecord(key("b"), 1000));
        assertFalse(deduper.shouldRecord(key("a"), 1000));
        assertFalse(deduper.shouldRecord(key("b"), 1000));
    }

    @Test
    public void recordsAgainAfterReset() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        deduper.reset();
        assertTrue(deduper.shouldRecord(key("a"), 1000));
    }

    @Test
    public void evictsLeastRecentlyRecordedKeysPastCap() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(10_000, 4);
        for (int i = 0; i < 5; i++) {
            assertTrue(deduper.shouldRecord(key("key-" + i), 1000 + i));
        }
        // "key-0" was recorded first, so it is the one dropped and can be recorded again, while the
        // most recently recorded key is still being tracked.
        assertTrue(deduper.shouldRecord(key("key-0"), 1010));
        assertFalse(deduper.shouldRecord(key("key-4"), 1010));
    }

    @Test
    public void reRecordingMovesKeyToMostRecentEndOfEvictionOrder() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 2);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertTrue(deduper.shouldRecord(key("b"), 1000));
        // "a" is re-recorded once its window elapses, which makes "b" the oldest tracked key.
        assertTrue(deduper.shouldRecord(key("a"), 1100));
        assertTrue(deduper.shouldRecord(key("c"), 1100));
        assertFalse(deduper.shouldRecord(key("a"), 1100));
    }

    @Test
    public void evictionPrefersKeysWhoseWindowHasElapsed() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100, 8);
        for (int i = 0; i < 2; i++) {
            assertTrue(deduper.shouldRecord(key("expired-" + i), 1000));
        }
        // The 7th of these takes the map past the cap. The keys recorded longest ago are the two
        // whose window has since elapsed, so those are the ones evicted and every live key is still
        // tracked.
        for (int i = 0; i < 7; i++) {
            assertTrue(deduper.shouldRecord(key("live-" + i), 1150));
        }
        for (int i = 0; i < 7; i++) {
            assertFalse(deduper.shouldRecord(key("live-" + i), 1150));
        }
    }

    @Test
    public void usesDefaultWindowAndCapWhenBuiltWithoutParameters() {
        // Ten minutes over 2000 keys.
        assertEquals(600_000, EvaluationExposureDeduper.DEFAULT_WINDOW_MILLIS);
        assertEquals(2_000, EvaluationExposureDeduper.DEFAULT_MAX_SIZE);

        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper();
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertFalse(deduper.shouldRecord(key("a"), 600_999));
        assertTrue(deduper.shouldRecord(key("a"), 601_000));

        for (int i = 0; i < EvaluationExposureDeduper.DEFAULT_MAX_SIZE - 1; i++) {
            assertTrue(deduper.shouldRecord(key("key-" + i), 601_000));
        }
        // "a" and these keys fill the cap exactly, so nothing has been evicted yet.
        assertFalse(deduper.shouldRecord(key("key-0"), 601_000));
    }

    @Test
    public void fallsBackToDefaultCapForNonPositiveMaxSize() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(10_000, 0);
        for (int i = 0; i < EvaluationExposureDeduper.DEFAULT_MAX_SIZE; i++) {
            assertTrue(deduper.shouldRecord(key("key-" + i), 1000));
        }
        assertFalse(deduper.shouldRecord(key("key-0"), 1000));
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
