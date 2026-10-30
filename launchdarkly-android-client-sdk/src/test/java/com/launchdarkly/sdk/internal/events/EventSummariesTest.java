package com.launchdarkly.sdk.internal.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventSummariesTest {
    private static final LDContext USER = LDContext.create("user");

    @Test
    public void aSummaryOfOneFlagIsNotSplit() {
        EventSummarizer summarizer = new EventSummarizer(USER);
        summarizer.summarizeEvent(1000, "only", 1, 0, LDValue.of(true), LDValue.of(false), USER);
        summarizer.summarizeEvent(1001, "only", 1, 0, LDValue.of(true), LDValue.of(false), USER);

        assertNull(EventSummaries.halve(summarizer.getSummaryAndReset()));
    }

    @Test
    public void theHalvesKeepTheContextAndPeriodAndShareTheFlagsBetweenThem() {
        EventSummarizer summarizer = new EventSummarizer(USER);
        for (int i = 0; i < 5; i++) {
            summarizer.summarizeEvent(1000 + i, "flag-" + i, 1, 0, LDValue.of(true), LDValue.of(false), USER);
        }
        EventSummarizer.EventSummary whole = summarizer.getSummaryAndReset();

        List<EventSummarizer.EventSummary> halves = EventSummaries.halve(whole);

        assertEquals(2, halves.size());
        assertEquals(2, halves.get(0).counters.size());
        assertEquals(3, halves.get(1).counters.size());
        Map<String, EventSummarizer.FlagInfo> rejoined = new HashMap<>();
        for (EventSummarizer.EventSummary half : halves) {
            assertSame(USER, half.context);
            assertEquals(1000, half.startDate);
            assertEquals(1004, half.endDate);
            rejoined.putAll(half.counters);
        }
        assertEquals(whole.counters, rejoined);
    }
}
