package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExposureDeduperTest {
    @Test
    public void disabledForNonPositiveWindow() {
        for (int window : new int[] { 0, -1 }) {
            ExposureDeduper deduper = new ExposureDeduper(window, 10);
            assertFalse(deduper.isEnabled());
            assertTrue(deduper.shouldRecord("a", 0));
            assertTrue(deduper.shouldRecord("a", 0));
        }
    }

    @Test
    public void suppressesRepeatsWithinWindow() {
        ExposureDeduper deduper = new ExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord("a", 1000));
        assertFalse(deduper.shouldRecord("a", 1000));
        assertFalse(deduper.shouldRecord("a", 1099));
    }

    @Test
    public void recordsAgainOnceWindowElapses() {
        ExposureDeduper deduper = new ExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord("a", 1000));
        assertTrue(deduper.shouldRecord("a", 1100));
        // Recording restarts the window rather than extending the original one.
        assertFalse(deduper.shouldRecord("a", 1150));
        assertTrue(deduper.shouldRecord("a", 1200));
    }

    @Test
    public void tracksKeysIndependently() {
        ExposureDeduper deduper = new ExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord("a", 1000));
        assertTrue(deduper.shouldRecord("b", 1000));
        assertFalse(deduper.shouldRecord("a", 1000));
        assertFalse(deduper.shouldRecord("b", 1000));
    }

    @Test
    public void recordsAgainAfterReset() {
        ExposureDeduper deduper = new ExposureDeduper(100, 10);
        assertTrue(deduper.shouldRecord("a", 1000));
        deduper.reset();
        assertTrue(deduper.shouldRecord("a", 1000));
    }

    @Test
    public void evictsLeastRecentlyRecordedKeysPastCap() {
        ExposureDeduper deduper = new ExposureDeduper(10_000, 4);
        for (int i = 0; i < 5; i++) {
            assertTrue(deduper.shouldRecord("key-" + i, 1000 + i));
        }
        // "key-0" was recorded first, so it is the one dropped and can be recorded again, while the
        // most recently recorded key is still being tracked.
        assertTrue(deduper.shouldRecord("key-0", 1010));
        assertFalse(deduper.shouldRecord("key-4", 1010));
    }

    @Test
    public void reRecordingMovesKeyToMostRecentEndOfEvictionOrder() {
        ExposureDeduper deduper = new ExposureDeduper(100, 2);
        assertTrue(deduper.shouldRecord("a", 1000));
        assertTrue(deduper.shouldRecord("b", 1000));
        // "a" is re-recorded once its window elapses, which makes "b" the oldest tracked key.
        assertTrue(deduper.shouldRecord("a", 1100));
        assertTrue(deduper.shouldRecord("c", 1100));
        assertFalse(deduper.shouldRecord("a", 1100));
    }

    @Test
    public void keepsLiveKeysWhenReclaimingExpiredOnesIsEnough() {
        // maxSize is 8 so that the batch term (maxSize / 4) is non-zero, which is what makes an
        // over-eager batch drop observable.
        ExposureDeduper deduper = new ExposureDeduper(100, 8);
        for (int i = 0; i < 2; i++) {
            assertTrue(deduper.shouldRecord("expired-" + i, 1000));
        }
        // The 7th of these exceeds the cap and triggers eviction. Reclaiming the two keys whose
        // window has elapsed brings the map back within the cap on its own, so every one of these
        // keys is still tracked and none of them should be reported again.
        for (int i = 0; i < 7; i++) {
            assertTrue(deduper.shouldRecord("live-" + i, 1150));
        }
        for (int i = 0; i < 7; i++) {
            assertFalse(deduper.shouldRecord("live-" + i, 1150));
        }
    }

    @Test
    public void fallsBackToDefaultCapForNonPositiveMaxSize() {
        ExposureDeduper deduper = new ExposureDeduper(10_000, 0);
        for (int i = 0; i < LDConfig.DEFAULT_FLAG_EXPOSURE_DEDUPE_MAX_SIZE; i++) {
            assertTrue(deduper.shouldRecord("key-" + i, 1000));
        }
        assertFalse(deduper.shouldRecord("key-0", 1000));
    }

    @Test
    public void recordsOnceWhenSameKeyIsCheckedConcurrently() throws Exception {
        ExposureDeduper deduper = new ExposureDeduper(60_000, 100);
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        boolean[] recorded = new boolean[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> recorded[index] = deduper.shouldRecord("a", 1000));
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
