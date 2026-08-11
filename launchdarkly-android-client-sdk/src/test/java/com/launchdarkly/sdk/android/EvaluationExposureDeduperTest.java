package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EvaluationExposureDeduper;
import com.launchdarkly.sdk.android.integrations.EvaluationExposureKey;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;

import org.junit.Test;


public class EvaluationExposureDeduperTest {
    /**
     * An exposure key that differs from every other one this test builds only by its flag key, so
     * that a test can talk about "the exposure of a" without spelling out the whole key.
     */
    private static EvaluationExposureKey key(String flagKey) {
        return new EvaluationExposureKey(
                "default", flagKey, LDValue.of("value"), 1, 2, "user-key");
    }

    /**
     * The same flag as {@link #key(String)}, resolved to a different variation.
     */
    private static EvaluationExposureKey otherResult(String flagKey) {
        return new EvaluationExposureKey(
                "default", flagKey, LDValue.of("other-value"), 3, 2, "user-key");
    }

    /**
     * The key the SDK builds for an evaluation it has no flag data for: one made before the client
     * has flags, or one of a flag that does not exist. Such an evaluation returns the default value,
     * so that is the value describing it, under the placeholders the SDK reports on events.
     */
    private static EvaluationExposureKey unknownFlag(String flagKey, LDValue defaultValue) {
        return new EvaluationExposureKey("default", flagKey, defaultValue,
                EvaluationDetail.NO_VARIATION, EventProcessor.NO_VERSION, "user-key");
    }

    @Test
    public void recordsEverythingForNonPositiveWindow() {
        for (int window : new int[] { 0, -1 }) {
            EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(window);
            assertTrue(deduper.shouldRecord(key("a"), 0));
            assertTrue(deduper.shouldRecord(key("a"), 0));
        }
    }

    @Test
    public void exposureKeyDistinguishesEveryComponent() {
        EvaluationExposureKey key = new EvaluationExposureKey(
                "default", "flag", LDValue.of("value"), 1, 2, "user-key");
        EvaluationExposureKey same = new EvaluationExposureKey(
                "default", "flag", LDValue.of("value"), 1, 2, "user-key");
        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());

        assertNotEquals(key, new EvaluationExposureKey(
                "default", "flag", LDValue.of("other-value"), 1, 2, "user-key"));
        assertNotEquals(key, new EvaluationExposureKey(
                "default", "other-flag", LDValue.of("value"), 1, 2, "user-key"));
        assertNotEquals(key, new EvaluationExposureKey(
                "default", "flag", LDValue.of("value"), 3, 2, "user-key"));
        assertNotEquals(key, new EvaluationExposureKey(
                "default", "flag", LDValue.of("value"), 1, 4, "user-key"));
        assertNotEquals(key, new EvaluationExposureKey(
                "default", "flag", LDValue.of("value"), 1, 2, "other-user-key"));
        // A hook shared across environments observes the same result once per environment.
        assertNotEquals(key, new EvaluationExposureKey(
                "other-env", "flag", LDValue.of("value"), 1, 2, "user-key"));
    }

    @Test
    public void exposureKeysWithNoFlagDataAreEqualWhenTheDefaultIs() {
        EvaluationExposureKey key = unknownFlag("flag", LDValue.of(false));
        EvaluationExposureKey same = unknownFlag("flag", LDValue.of(false));
        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());

        assertNotEquals(key, unknownFlag("flag", LDValue.of(true)));
    }

    @Test
    public void suppressesRepeatsWithinWindow() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertFalse(deduper.shouldRecord(key("a"), 1000));
        assertFalse(deduper.shouldRecord(key("a"), 1099));
    }

    @Test
    public void recordsAgainOnceWindowElapses() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertTrue(deduper.shouldRecord(key("a"), 1100));
        // Recording restarts the window rather than extending the original one.
        assertFalse(deduper.shouldRecord(key("a"), 1150));
        assertTrue(deduper.shouldRecord(key("a"), 1200));
    }

    @Test
    public void tracksFlagsIndependently() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertTrue(deduper.shouldRecord(key("b"), 1000));
        assertFalse(deduper.shouldRecord(key("a"), 1000));
        assertFalse(deduper.shouldRecord(key("b"), 1000));
    }

    @Test
    public void reportsAFlagAgainAsSoonAsItsResultChanges() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        assertTrue(deduper.shouldRecord(otherResult("a"), 1010));
        assertFalse(deduper.shouldRecord(otherResult("a"), 1020));
        // Only the result the flag reported last is tracked, so flipping back is a change too and the
        // hook is told about it rather than being left to think the flag never returned to it.
        assertTrue(deduper.shouldRecord(key("a"), 1030));
        assertFalse(deduper.shouldRecord(key("a"), 1040));
    }

    @Test
    public void reportsAgainWhenOnlyTheFlagValueChanges() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100);
        EvaluationExposureKey first = new EvaluationExposureKey(
                "default", "flag", LDValue.of("first"), 1, 2, "user-key");
        EvaluationExposureKey second = new EvaluationExposureKey(
                "default", "flag", LDValue.of("second"), 1, 2, "user-key");

        assertTrue(deduper.shouldRecord(first, 1000));
        assertTrue(deduper.shouldRecord(second, 1010));
        assertFalse(deduper.shouldRecord(second, 1020));
    }

    @Test
    public void treatsEvaluationsWithNoFlagDataResolvingToTheSameDefaultAsOneExposure() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100);

        // Evaluations the SDK has no flag data for return the default value, and repeats of that result
        // are a repeat like any other.
        assertTrue(deduper.shouldRecord(unknownFlag("a", LDValue.of(false)), 1000));
        assertFalse(deduper.shouldRecord(unknownFlag("a", LDValue.of(false)), 1010));

        // A different default is a different result, because it is a different value returned to the
        // application.
        assertTrue(deduper.shouldRecord(unknownFlag("a", LDValue.of(true)), 1020));
        assertFalse(deduper.shouldRecord(unknownFlag("a", LDValue.of(true)), 1030));
    }

    @Test
    public void reportsAgainWhenTheFlagBecomesKnown() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100);
        assertTrue(deduper.shouldRecord(unknownFlag("a", LDValue.of("value")), 1000));

        // The data arriving is a change of result even when the flag resolves to the value the default
        // had already produced, because the variation and version are no longer the placeholders the
        // SDK uses for a flag it did not find. So is the flag going away again.
        assertTrue(deduper.shouldRecord(key("a"), 1010));
        assertFalse(deduper.shouldRecord(key("a"), 1020));
        assertTrue(deduper.shouldRecord(unknownFlag("a", LDValue.of("value")), 1030));
    }

    @Test
    public void tracksTheSameFlagSeparatelyPerEnvironment() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100);
        EvaluationExposureKey primary = new EvaluationExposureKey("default", "flag", 1, 2, "user-key");
        EvaluationExposureKey secondary = new EvaluationExposureKey("other", "flag", 3, 4, "user-key");

        // A hook set on the configuration is shared by the clients for every environment, so its
        // deduper sees both. Neither environment may look to the other like its result changing.
        assertTrue(deduper.shouldRecord(primary, 1000));
        assertTrue(deduper.shouldRecord(secondary, 1000));
        assertFalse(deduper.shouldRecord(primary, 1010));
        assertFalse(deduper.shouldRecord(secondary, 1010));
    }

    @Test
    public void recordsAgainAfterReset() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(100);
        assertTrue(deduper.shouldRecord(key("a"), 1000));
        deduper.reset();
        assertTrue(deduper.shouldRecord(key("a"), 1000));
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
    public void tracksEveryFlagTheApplicationEvaluates() {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(600_000);
        for (int i = 0; i < 2_000; i++) {
            assertTrue(deduper.shouldRecord(key("key-" + i), 1000));
        }

        // Records accumulate; the first flag is still suppressed after two thousand others have been recorded.
        assertFalse(deduper.shouldRecord(key("key-0"), 1000));
        assertFalse(deduper.shouldRecord(key("key-1999"), 1000));
    }

    @Test
    public void recordsOnceWhenSameKeyIsCheckedConcurrently() throws Exception {
        EvaluationExposureDeduper deduper = new EvaluationExposureDeduper(60_000);
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
