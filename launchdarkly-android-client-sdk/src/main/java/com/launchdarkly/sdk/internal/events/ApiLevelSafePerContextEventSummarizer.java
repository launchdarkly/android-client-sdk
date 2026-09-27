package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PerContextEventSummarizer} without the Java 8 library calls that Android lacks below API 24.
 * <p>
 * <b>Temporary, and only here because it has to be.</b> java-sdk-internal's summarizer calls
 * {@code Map.computeIfAbsent} with a {@code java.util.function.Function}, neither of which exists before
 * API 24. An application that has not turned on core library desugaring gets a
 * {@code NoClassDefFoundError} at the first evaluation it summarizes, and lint cannot see it because the
 * call is inside a library jar. A per-context summary can only be built through {@link EventSummarizer}'s
 * package-private members, which is why this sits in java-sdk-internal's package rather than the SDK's.
 * <p>
 * Delete it, and go back to {@link PerContextEventSummarizer}, once java-sdk-internal ships a summarizer
 * that uses a plain get and put. The behavior is otherwise that class's, line for line.
 */
public final class ApiLevelSafePerContextEventSummarizer implements EventSummarizerInterface {
    private final Map<LDContext, EventSummarizer> summarizersByContext = new HashMap<>();

    @Override
    public void summarizeEvent(
            long timestamp,
            String flagKey,
            int flagVersion,
            int variation,
            LDValue value,
            LDValue defaultValue,
            LDContext context
    ) {
        EventSummarizer summarizer = summarizersByContext.get(context);
        if (summarizer == null) {
            summarizer = new EventSummarizer(context);
            summarizersByContext.put(context, summarizer);
        }
        summarizer.summarizeEvent(timestamp, flagKey, flagVersion, variation, value, defaultValue, context);
    }

    @Override
    public List<EventSummarizer.EventSummary> getSummariesAndReset() {
        List<EventSummarizer.EventSummary> summaries = new ArrayList<>();
        for (EventSummarizer summarizer : summarizersByContext.values()) {
            EventSummarizer.EventSummary summary = summarizer.getSummaryAndReset();
            if (!summary.isEmpty()) {
                summaries.add(summary);
            }
        }
        summarizersByContext.clear();
        return summaries;
    }

    @Override
    public void restoreTo(List<EventSummarizer.EventSummary> previousSummaries) {
        summarizersByContext.clear();
        for (EventSummarizer.EventSummary summary : previousSummaries) {
            if (summary.context != null && !summary.isEmpty()) {
                EventSummarizer summarizer = new EventSummarizer(summary.context);
                summarizer.restoreTo(summary);
                summarizersByContext.put(summary.context, summarizer);
            }
        }
    }

    @Override
    public boolean isEmpty() {
        for (EventSummarizer summarizer : summarizersByContext.values()) {
            if (!summarizer.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void clear() {
        summarizersByContext.clear();
    }
}
