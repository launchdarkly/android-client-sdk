package com.launchdarkly.sdk.internal.events;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Splits a summary too large to store or send whole. In java-sdk-internal's package for the reason
 * {@link SameContextPerContextEventSummarizer} is: a summary can only be built through
 * {@link EventSummarizer}'s package-private members.
 */
public final class EventSummaries {
    private EventSummaries() {
    }

    /**
     * Splits a summary's flags between two summaries of the same context and period. LaunchDarkly sums
     * the counters of every summary it receives, so the two count exactly what the one did.
     *
     * @param summary a summary nothing is counting into any more; the halves share its counters
     * @return the two halves, or null for a summary of one flag, which cannot be split
     */
    public static List<EventSummarizer.EventSummary> halve(EventSummarizer.EventSummary summary) {
        if (summary.counters.size() < 2) {
            return null;
        }
        List<String> flagKeys = new ArrayList<>(summary.counters.keySet());
        Collections.sort(flagKeys);
        EventSummarizer.EventSummary first = emptyLike(summary);
        EventSummarizer.EventSummary second = emptyLike(summary);
        int middle = flagKeys.size() / 2;
        for (int i = 0; i < flagKeys.size(); i++) {
            String flagKey = flagKeys.get(i);
            (i < middle ? first : second).counters.put(flagKey, summary.counters.get(flagKey));
        }
        return Arrays.asList(first, second);
    }

    private static EventSummarizer.EventSummary emptyLike(EventSummarizer.EventSummary summary) {
        EventSummarizer.EventSummary empty = new EventSummarizer.EventSummary(summary.context);
        empty.startDate = summary.startDate;
        empty.endDate = summary.endDate;
        return empty;
    }
}
