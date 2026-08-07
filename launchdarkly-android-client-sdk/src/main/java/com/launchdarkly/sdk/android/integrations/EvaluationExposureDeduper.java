package com.launchdarkly.sdk.android.integrations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
 * This class is the SDK's implementation: it remembers the result each flag last reported, and tells
 * the hook about the flag again as soon as that result changes, or once the window elapses while it
 * stays the same. Tracking one result per flag rather than every result seen keeps a flag that flips
 * back and forth from hiding the flips, and bounds the cache by the size of the flag set. The cap is
 * a safety net on top of that, evicting the flag recorded longest ago. Subclass this to implement a
 * different policy; only {@link #shouldRecord(EvaluationExposureKey, long)} and {@link #reset()} are
 * called by the SDK.
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
     * The number of flags tracked by a deduper built without a positive cap of its own: 2000.
     */
    public static final int DEFAULT_MAX_SIZE = 2_000;

    private static final EvaluationExposureDeduper DISABLED = new Disabled();

    private final long windowMillis;
    private final int maxSize;

    // Insertion-ordered, and each recording re-inserts its flag, so the eldest entry is the flag
    // recorded longest ago. That makes it the right one to evict: if any tracked window has elapsed,
    // the eldest entry's has, and dropping it costs nothing because an elapsed window no longer
    // suppresses anything. Guarded by the instance lock, as is every access below.
    private final LinkedHashMap<TrackedFlag, LastReported> lastReported =
            new LinkedHashMap<TrackedFlag, LastReported>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TrackedFlag, LastReported> eldest) {
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
     * @param maxSize the maximum number of flags to track, counting a flag once per environment it is
     *                evaluated in; zero or negative falls back to {@link #DEFAULT_MAX_SIZE}
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
     * if so starts a new dedupe window for the flag.
     * <p>
     * The SDK calls this once per evaluation per hook. This implementation answers true when the flag
     * is reporting a different result than it last did, and when the window has elapsed on the result
     * it is repeating. See {@link EvaluationExposureKey} for what makes two evaluations the same
     * result.
     *
     * @param key the key identifying the evaluation result
     * @param nowMillis the current time in milliseconds since the epoch
     * @return true if the hook should observe this evaluation, false if it should be suppressed
     */
    public synchronized boolean shouldRecord(EvaluationExposureKey key, long nowMillis) {
        if (windowMillis <= 0) {
            return true;
        }

        TrackedFlag flag = new TrackedFlag(key);
        LastReported reported = lastReported.get(flag);
        if (reported == null) {
            lastReported.put(flag, new LastReported(key, nowMillis));
            return true;
        }
        if (reported.atMillis > nowMillis - windowMillis && reported.isSameResultAs(key)) {
            return false;
        }

        // The flag is being reported again, so its record is reused rather than replaced, and
        // re-inserted to move it to the most recent end of the iteration order. The map evicts the
        // eldest entry itself if that ever takes it past the cap.
        reported.update(key, nowMillis);
        lastReported.remove(flag);
        lastReported.put(flag, reported);
        return true;
    }

    /**
     * Clears all recorded exposures, so the next evaluation of each is reported again. The SDK calls
     * this when the evaluation context changes.
     */
    public synchronized void reset() {
        lastReported.clear();
    }

    /**
     * The flag a record belongs to. The environment is part of it because a hook set on the
     * configuration is one instance shared by the clients for every environment in
     * {@code secondaryMobileKeys}: were the environments to share a record, each would look like the
     * other having changed its result, and neither would ever be suppressed.
     */
    private static final class TrackedFlag {
        private final String environmentName;
        private final String flagKey;
        private final int hashCode;

        TrackedFlag(EvaluationExposureKey key) {
            this.environmentName = key.getEnvironmentName();
            this.flagKey = key.getFlagKey();
            this.hashCode = 31 * Objects.hashCode(environmentName) + Objects.hashCode(flagKey);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TrackedFlag)) {
                return false;
            }

            TrackedFlag o = (TrackedFlag) other;
            return hashCode == o.hashCode
                    && Objects.equals(flagKey, o.flagKey)
                    && Objects.equals(environmentName, o.environmentName);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    /**
     * The result a flag last reported, and when. Mutable so that a flag costs one record for as long
     * as it is tracked, however often its result changes.
     */
    private static final class LastReported {
        private int variation;
        private int flagVersion;
        private boolean inExperiment;
        private String fullyQualifiedContextKey;
        private long atMillis;

        LastReported(EvaluationExposureKey key, long atMillis) {
            update(key, atMillis);
        }

        void update(EvaluationExposureKey key, long atMillis) {
            this.variation = key.getVariation();
            this.flagVersion = key.getFlagVersion();
            this.inExperiment = key.isInExperiment();
            this.fullyQualifiedContextKey = key.getFullyQualifiedContextKey();
            this.atMillis = atMillis;
        }

        boolean isSameResultAs(EvaluationExposureKey key) {
            return variation == key.getVariation()
                    && flagVersion == key.getFlagVersion()
                    && inExperiment == key.isInExperiment()
                    && Objects.equals(fullyQualifiedContextKey, key.getFullyQualifiedContextKey());
        }
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
