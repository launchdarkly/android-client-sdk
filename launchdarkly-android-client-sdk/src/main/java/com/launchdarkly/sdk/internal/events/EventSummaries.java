package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.LDContext;

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

    /**
     * @return the summary's flag keys, sorted
     */
    public static List<String> flagKeys(EventSummarizer.EventSummary summary) {
        List<String> flagKeys = new ArrayList<>(summary.counters.keySet());
        Collections.sort(flagKeys);
        return flagKeys;
    }

    /**
     * @return {@link #withFlags(EventSummarizer.EventSummary, List, LDContext)} for the summary's own context:
     *   a part to send
     */
    public static EventSummarizer.EventSummary withFlags(EventSummarizer.EventSummary summary,
                                                         List<String> flagKeys) {
        return withFlags(summary, flagKeys, summary.context);
    }

    /**
     * Some of a summary's flags, for a context of the caller's choosing, such as a small stand-in for
     * measuring how much the flags take without encoding the context again.
     *
     * @param summary a summary nothing is counting into any more; the part shares its counters
     * @param flagKeys flags the summary has
     * @param context the context the part is for
     * @return a summary of the same period with only those flags
     */
    public static EventSummarizer.EventSummary withFlags(EventSummarizer.EventSummary summary,
                                                         List<String> flagKeys, LDContext context) {
        EventSummarizer.EventSummary part = emptyLike(summary, context);
        for (String flagKey : flagKeys) {
            part.counters.put(flagKey, summary.counters.get(flagKey));
        }
        return part;
    }

    private static EventSummarizer.EventSummary emptyLike(EventSummarizer.EventSummary summary) {
        return emptyLike(summary, summary.context);
    }

    private static EventSummarizer.EventSummary emptyLike(EventSummarizer.EventSummary summary,
                                                          LDContext context) {
        EventSummarizer.EventSummary empty = new EventSummarizer.EventSummary(context);
        empty.startDate = summary.startDate;
        empty.endDate = summary.endDate;
        return empty;
    }
}
