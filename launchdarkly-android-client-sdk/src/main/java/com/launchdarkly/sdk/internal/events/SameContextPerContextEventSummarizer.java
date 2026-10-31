package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PerContextEventSummarizer} with a fast path for the context it saw last, and without the Java 8
 * library calls that Android lacks below API 24.
 * <p>
 * The fast path is the reason this outlives the API-level fix. {@link LDContext#hashCode()} is not cached:
 * every call flattens and hashes every attribute, so a map keyed on the context costs one full hash per
 * evaluation, usually on the main thread. An application evaluates against the same {@code LDContext}
 * instance until it identifies again, so comparing references first skips that hash on nearly every call.
 * <p>
 * The API level: java-sdk-internal's summarizer calls {@code Map.computeIfAbsent} with a
 * {@code java.util.function.Function}, neither of which exists before API 24. An application that has not
 * turned on core library desugaring gets a {@code NoClassDefFoundError} at the first evaluation it
 * summarizes, and lint cannot see it because the call is inside a library jar.
 * <p>
 * A per-context summary can only be built through {@link EventSummarizer}'s package-private members,
 * which is why this sits in java-sdk-internal's package rather than the SDK's. Not thread-safe; the
 * caller serializes every call.
 */
public final class SameContextPerContextEventSummarizer implements EventSummarizerInterface {
    private final Map<LDContext, EventSummarizer> summarizersByContext = new HashMap<>();

    /** The context the last evaluation was counted for, compared by reference, and its summarizer. */
    private LDContext lastContext;
    private EventSummarizer lastSummarizer;

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
        EventSummarizer summarizer;
        if (context == lastContext && lastSummarizer != null) {
            summarizer = lastSummarizer;
        } else {
            summarizer = summarizersByContext.get(context);
            if (summarizer == null) {
                summarizer = new EventSummarizer(context);
                summarizersByContext.put(context, summarizer);
            }
            lastContext = context;
            lastSummarizer = summarizer;
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
        forgetLastContext();
        return summaries;
    }

    @Override
    public void restoreTo(List<EventSummarizer.EventSummary> previousSummaries) {
        summarizersByContext.clear();
        forgetLastContext();
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
        forgetLastContext();
    }

    /** Required wherever the map is cleared, or the fast path would count into a summarizer no longer in it. */
    private void forgetLastContext() {
        lastContext = null;
        lastSummarizer = null;
    }
}
