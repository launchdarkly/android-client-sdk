package com.launchdarkly.sdk.android.integrations;

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
 * bounds the number of tracked keys, evicting the least recently recorded one when the cap is
 * exceeded. Subclass it to implement a different policy; only
 * {@link #shouldRecord(EvaluationExposureKey, long)} and {@link #reset()} are called by the SDK.
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

    // Insertion-ordered, and each recording re-inserts its key, so the eldest entry is the one
    // recorded longest ago. That makes it the right one to evict: if any tracked window has elapsed,
    // the eldest entry's has, and dropping it costs nothing because an elapsed window no longer
    // suppresses anything. Guarded by the instance lock, as is every access below.
    private final LinkedHashMap<EvaluationExposureKey, Long> lastRecordedAt =
            new LinkedHashMap<EvaluationExposureKey, Long>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<EvaluationExposureKey, Long> eldest) {
                    return size() > maxSize;
                }
            };

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
     * The SDK calls this once per evaluation per hook. See {@link EvaluationExposureKey} for what makes
     * two evaluations the same exposure.
     *
     * @param key the key identifying the evaluation result
     * @param nowMillis the current time in milliseconds since the epoch
     * @return true if the hook should observe this evaluation, false if it should be suppressed
     */
    public synchronized boolean shouldRecord(EvaluationExposureKey key, long nowMillis) {
        if (windowMillis <= 0) {
            return true;
        }

        Long last = lastRecordedAt.get(key);
        if (last != null && last > nowMillis - windowMillis) {
            return false;
        }

        // Remove before putting so the key moves to the most recent end of the iteration order. The
        // map evicts the eldest entry itself once this put takes it past the cap.
        lastRecordedAt.remove(key);
        lastRecordedAt.put(key, nowMillis);
        return true;
    }

    /**
     * Clears all recorded exposures, so the next evaluation of each is reported again. The SDK calls
     * this when the evaluation context changes.
     */
    public synchronized void reset() {
        lastRecordedAt.clear();
    }

    private static final class Disabled extends EvaluationExposureDeduper {
        Disabled() {
            super(0, 0);
        }

        @Override
        public boolean shouldRecord(EvaluationExposureKey key, long nowMillis) {
            return true;
        }

        @Override
        public void reset() {
        }
    }
}
