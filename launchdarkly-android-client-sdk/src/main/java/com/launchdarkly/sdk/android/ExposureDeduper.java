package com.launchdarkly.sdk.android;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks recently recorded feature flag exposures so that repeated evaluations resolving to the same
 * result do not report a new evaluation event within a configured time window.
 * <p>
 * Each unique exposure key is only recorded once per window. The number of tracked keys is bounded;
 * when the cap is exceeded the least recently recorded keys are evicted.
 * <p>
 * This class is thread-safe. Evaluations may be made from any thread, so the check of the window and
 * the update of it are performed together under a single lock.
 */
final class ExposureDeduper {
    private final long windowMillis;
    private final int maxSize;

    // Insertion-ordered so that iteration visits the least recently recorded key first. Guarded by
    // the instance lock, as is every access below.
    private final LinkedHashMap<String, Long> lastRecordedAt = new LinkedHashMap<>();

    /**
     * @param windowMillis the dedupe window in milliseconds; zero or negative disables deduplication,
     *                     so every exposure is recorded
     * @param maxSize the maximum number of exposure keys to track; zero or negative falls back to
     *                {@link LDConfig#DEFAULT_FLAG_EXPOSURE_DEDUPE_MAX_SIZE}
     */
    ExposureDeduper(int windowMillis, int maxSize) {
        this.windowMillis = windowMillis;
        this.maxSize = maxSize > 0 ? maxSize : LDConfig.DEFAULT_FLAG_EXPOSURE_DEDUPE_MAX_SIZE;
    }

    boolean isEnabled() {
        return windowMillis > 0;
    }

    /**
     * Returns whether an exposure for the given key should be recorded, and if so starts a new dedupe
     * window for it.
     *
     * @param key a stable key identifying the evaluation result
     * @param nowMillis the current time in milliseconds since the epoch
     * @return true if the exposure should be recorded, false if it should be suppressed
     */
    synchronized boolean shouldRecord(String key, long nowMillis) {
        if (!isEnabled()) {
            return true;
        }

        Long last = lastRecordedAt.get(key);
        if (last != null && last > nowMillis - windowMillis) {
            return false;
        }

        // Remove before putting so the key moves to the most recent end of the iteration order.
        lastRecordedAt.remove(key);
        lastRecordedAt.put(key, nowMillis);

        if (lastRecordedAt.size() > maxSize) {
            evict(nowMillis);
        }
        return true;
    }

    /**
     * Clears all recorded exposures. Called when the evaluation context changes.
     */
    synchronized void reset() {
        lastRecordedAt.clear();
    }

    private void evict(long nowMillis) {
        // Keys whose window has already elapsed no longer change the outcome of shouldRecord, so
        // reclaim those first. They sort before any live key, so this stops at the first live one.
        long cutoff = nowMillis - windowMillis;
        for (Iterator<Map.Entry<String, Long>> it = lastRecordedAt.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() > cutoff) {
                break;
            }
            it.remove();
        }

        // Evict a batch rather than a single key, so that a workload tracking more live keys than
        // maxSize doesn't pay for an eviction on every subsequent exposure.
        int dropCount = lastRecordedAt.size() - maxSize + maxSize / 4;
        for (Iterator<Map.Entry<String, Long>> it = lastRecordedAt.entrySet().iterator();
             dropCount > 0 && it.hasNext();
             dropCount--) {
            it.next();
            it.remove();
        }
    }
}
