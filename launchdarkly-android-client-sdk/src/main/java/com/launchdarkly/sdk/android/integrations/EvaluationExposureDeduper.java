package com.launchdarkly.sdk.android.integrations;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides whether a hook should be told about an evaluation, so that repeated evaluations resolving
 * to the same result do not invoke the hook again within a time window.
 * <p>
 * Deduplication is opt-in per hook: a hook is told about every evaluation until you give it a
 * deduper with {@link Hook#evaluationExposureDeduper(int, int)}.
 *
 * <pre><code>
 *     Components.hooks()
 *         .addHook(new MetricsHook())                                // told about every evaluation
 *         .addHook(new ObservabilityHook().evaluationExposureDeduper())  // default window and cap
 *         .addHook(new TelemetryHook().evaluationExposureDeduper(30_000, 5_000))
 *         .addHook(new ExperimentHook().evaluationExposureDeduper(myCustomDeduper))
 * </code></pre>
 * <p>
 * This class is the SDK's implementation: it records each unique exposure key once per window and
 * bounds the number of tracked keys, evicting the least recently recorded ones when the cap is
 * exceeded. Subclass it to implement a different policy; only {@link #shouldRecord(String, long)}
 * and {@link #reset()} are called by the SDK.
 * <p>
 * A deduper is consulted once per evaluation, before the series opens, so a suppressed evaluation
 * invokes neither {@code beforeEvaluation} nor {@code afterEvaluation}. Implementations must be
 * thread-safe, because evaluations may be made from any thread. Give each hook its own instance
 * unless you intend hooks to share a window: the first hook to be told about an exposure starts the
 * window that suppresses the rest.
 */
public class EvaluationExposureDeduper {
    /**
     * The dedupe window used by a deduper built without a window of its own: 10 minutes, in
     * milliseconds.
     */
    public static final int DEFAULT_WINDOW_MILLIS = 600_000;

    /**
     * The number of exposure keys tracked by a deduper built without a positive cap of its own: 2000.
     */
    public static final int DEFAULT_MAX_SIZE = 2_000;

    private static final EvaluationExposureDeduper DISABLED = new Disabled();

    private final long windowMillis;
    private final int maxSize;

    // Insertion-ordered so that iteration visits the least recently recorded key first. Guarded by
    // the instance lock, as is every access below.
    private final LinkedHashMap<String, Long> lastRecordedAt = new LinkedHashMap<>();

    /**
     * Creates a deduper with a window of {@link #DEFAULT_WINDOW_MILLIS} over at most
     * {@link #DEFAULT_MAX_SIZE} exposure keys.
     */
    public EvaluationExposureDeduper() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_MAX_SIZE);
    }

    /**
     * @param windowMillis the dedupe window in milliseconds; zero or negative disables
     *                     deduplication, so every evaluation reaches the hook
     * @param maxSize the maximum number of exposure keys to track; zero or negative falls back to
     *                {@link #DEFAULT_MAX_SIZE}
     */
    public EvaluationExposureDeduper(int windowMillis, int maxSize) {
        this.windowMillis = windowMillis;
        this.maxSize = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
    }

    /**
     * Returns a deduper that suppresses nothing, so its hook is told about every evaluation.
     * <p>
     * This is what a hook gets when it is registered without a deduper, so passing it is only useful
     * to state that intent explicitly. The returned instance holds no state and may be given to any
     * number of hooks.
     *
     * @return a deduper that never suppresses an evaluation
     */
    public static EvaluationExposureDeduper disabled() {
        return DISABLED;
    }

    /**
     * Returns whether the hook should be told about the evaluation identified by the given key, and
     * if so starts a new dedupe window for it.
     * <p>
     * The SDK calls this once per evaluation per hook. The key identifies the evaluation result: two
     * evaluations share a key when they resolve to the same variation of the same flag version, with
     * the same experiment status, for the same context, in the same environment. Evaluations made
     * against different environments never share a key, so a hook shared by the clients for several
     * environments observes each of them.
     *
     * @param key a stable key identifying the evaluation result
     * @param nowMillis the current time in milliseconds since the epoch
     * @return true if the hook should observe this evaluation, false if it should be suppressed
     */
    public synchronized boolean shouldRecord(String key, long nowMillis) {
        if (windowMillis <= 0) {
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
     * Clears all recorded exposures, so the next evaluation of each is reported again. The SDK calls
     * this when the evaluation context changes.
     */
    public synchronized void reset() {
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

        if (lastRecordedAt.size() <= maxSize) {
            // Reclaiming expired keys was enough. Dropping live keys past this point would report
            // their next identical evaluation again.
            return;
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

    private static final class Disabled extends EvaluationExposureDeduper {
        Disabled() {
            super(0, 0);
        }

        @Override
        public boolean shouldRecord(String key, long nowMillis) {
            return true;
        }

        @Override
        public void reset() {
        }
    }
}
