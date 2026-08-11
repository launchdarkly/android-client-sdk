package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.LDValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Decides whether a hook should be told about an evaluation, so that repeated evaluations resolving
 * to the same result do not invoke the hook again within a time window.
 * <p>
 * Deduplication is opt-in per hook: a hook is told about every evaluation until you wrap it in a
 * {@link DedupingHook}, which is what consults a deduper.
 *
 * <pre><code>
 *     Components.hooks()
 *         .addHook(new MetricsHook())                        // told about every evaluation
 *         .addHook(new DedupingHook(new ObservabilityHook())) // default window
 *         .addHook(new DedupingHook(new TelemetryHook(), 30_000))
 *         .addHook(new DedupingHook(new ExperimentHook(), myCustomDeduper))
 * </code></pre>
 * <p>
 * This class is the SDK's implementation: it remembers the result each flag last reported, and tells
 * the hook about the flag again as soon as that result changes, or once the window elapses while it
 * stays the same. Tracking one result per flag rather than every result seen keeps a flag that flips
 * back and forth from hiding the flips, and holds one record per flag the application evaluates, so
 * the window is the only thing there is to configure. Subclass this to implement a different policy;
 * only {@link #shouldRecord(EvaluationExposureKey, long)} and {@link #reset()} are called by
 * {@link DedupingHook}.
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

    private final long windowMillis;

    // Last result reported for each flag, per environment. Entries stay until reset().
    // Accessed only from the synchronized methods on this instance.
    private final Map<TrackedFlag, LastReported> lastReported = new HashMap<>();

    /**
     * Creates a deduper with a window of {@link #DEFAULT_WINDOW_MILLIS}.
     */
    public EvaluationExposureDeduper() {
        this(DEFAULT_WINDOW_MILLIS);
    }

    /**
     * @param windowMillis the dedupe window in milliseconds; zero or negative disables
     *                     deduplication, so every evaluation reaches the hook
     */
    public EvaluationExposureDeduper(int windowMillis) {
        this.windowMillis = windowMillis;
    }

    /**
     * Returns whether the hook should be told about the evaluation identified by the given key, and
     * if so starts a new dedupe window for the flag.
     * <p>
     * {@link DedupingHook} calls this once per evaluation. This implementation answers true when the flag
     * is reporting a different result than it last did, and when the window has elapsed on the result
     * it is repeating. See {@link EvaluationExposureKey} for what makes two evaluations the same
     * result.
     *
     * @param key the key identifying the evaluation result
     * @param nowMillis a reading of a clock that counts from an arbitrary point, in milliseconds.
     *                  {@link DedupingHook} passes {@code SystemClock.elapsedRealtime()}, so that
     *                  correcting the device clock cannot stretch a window. Only differences between
     *                  readings are meaningful: this is not a time of day, and comparing it with
     *                  {@code System.currentTimeMillis()} is a mistake.
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

        reported.update(key, nowMillis);
        return true;
    }

    /**
     * Clears all recorded exposures, so the next evaluation of each is reported again.
     * {@link DedupingHook} calls this when the evaluation context changes.
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
        private LDValue value;
        private int variation;
        private int flagVersion;
        private boolean inExperiment;
        private String fullyQualifiedContextKey;
        private long atMillis;

        LastReported(EvaluationExposureKey key, long atMillis) {
            update(key, atMillis);
        }

        void update(EvaluationExposureKey key, long atMillis) {
            this.value = key.getValue();
            this.variation = key.getVariation();
            this.flagVersion = key.getFlagVersion();
            this.inExperiment = key.isInExperiment();
            this.fullyQualifiedContextKey = key.getFullyQualifiedContextKey();
            this.atMillis = atMillis;
        }

        boolean isSameResultAs(EvaluationExposureKey key) {
            return Objects.equals(value, key.getValue())
                    && variation == key.getVariation()
                    && flagVersion == key.getFlagVersion()
                    && inExperiment == key.isInExperiment()
                    && Objects.equals(fullyQualifiedContextKey, key.getFullyQualifiedContextKey());
        }
    }
}
