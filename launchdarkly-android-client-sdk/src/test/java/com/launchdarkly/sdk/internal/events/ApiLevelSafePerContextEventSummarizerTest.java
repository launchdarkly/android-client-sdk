package com.launchdarkly.sdk.internal.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import org.junit.Test;

import java.util.List;

/**
 * Checks the API-level-safe copy against the summarizer it stands in for. The JVM these run on has
 * {@code computeIfAbsent}, so the original can be run alongside it and the two compared directly.
 */
public class ApiLevelSafePerContextEventSummarizerTest {
    private static final LDContext USER = LDContext.builder("user").name("User").set("plan", "pro").build();
    private static final LDContext DEVICE = LDContext.create(ContextKind.of("device"), "device");
    private static final LDContext MULTI = LDContext.createMulti(USER, DEVICE);

    private static void summarizeTheSame(EventSummarizerInterface a, EventSummarizerInterface b) {
        Object[][] evaluations = {
                {1000L, "flag-a", 1, 0, LDValue.of(true), LDValue.of(false), USER},
                {1001L, "flag-a", 1, 0, LDValue.of(true), LDValue.of(false), USER},
                {1002L, "flag-a", 2, 1, LDValue.of(false), LDValue.of(false), USER},
                {999L, "flag-b", -1, -1, LDValue.of("x"), LDValue.of("x"), DEVICE},
                {1003L, "flag-a", 1, 0, LDValue.of(true), LDValue.of(false), MULTI},
                {1004L, "flag-c", 3, 2, LDValue.of(2.5), LDValue.ofNull(), LDContext.builder("user")
                        .name("User").set("plan", "pro").build()},
        };
        for (Object[] e : evaluations) {
            for (EventSummarizerInterface s : new EventSummarizerInterface[]{a, b}) {
                s.summarizeEvent((Long) e[0], (String) e[1], (Integer) e[2], (Integer) e[3],
                        (LDValue) e[4], (LDValue) e[5], (LDContext) e[6]);
            }
        }
    }

    @Test
    public void summarizesAsTheOriginalDoes() {
        PerContextEventSummarizer original = new PerContextEventSummarizer();
        ApiLevelSafePerContextEventSummarizer copy = new ApiLevelSafePerContextEventSummarizer();
        summarizeTheSame(original, copy);

        assertFalse(copy.isEmpty());
        List<EventSummarizer.EventSummary> expected = original.getSummariesAndReset();
        List<EventSummarizer.EventSummary> actual = copy.getSummariesAndReset();
        // An equal context built separately shares a summary, so three contexts, not four.
        assertEquals(3, actual.size());
        assertEquals(expected, actual);
        assertTrue(copy.isEmpty());
        assertTrue(copy.getSummariesAndReset().isEmpty());
    }

    @Test
    public void restoresAsTheOriginalDoes() {
        PerContextEventSummarizer original = new PerContextEventSummarizer();
        ApiLevelSafePerContextEventSummarizer copy = new ApiLevelSafePerContextEventSummarizer();
        summarizeTheSame(original, copy);
        List<EventSummarizer.EventSummary> taken = copy.getSummariesAndReset();
        original.restoreTo(original.getSummariesAndReset());
        copy.restoreTo(taken);

        summarizeTheSame(original, copy);

        assertEquals(original.getSummariesAndReset(), copy.getSummariesAndReset());
    }

    @Test
    public void clearForgetsEverything() {
        ApiLevelSafePerContextEventSummarizer copy = new ApiLevelSafePerContextEventSummarizer();
        copy.summarizeEvent(1000, "flag", 1, 0, LDValue.of(true), LDValue.of(false), USER);

        copy.clear();

        assertTrue(copy.isEmpty());
        assertTrue(copy.getSummariesAndReset().isEmpty());
    }
}
