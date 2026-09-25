package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.internal.events.Event;

import java.util.Collection;
import java.util.Map;

/**
 * Writes the events this SDK records, reusing a context's encoding across the run of events that share
 * it.
 * <p>
 * <b>Why this exists rather than {@code EventOutputFormatter}.</b> Every event carries its whole context,
 * and an application identifies once and then evaluates many times, so a run of events encodes the same
 * context over and over. That repetition is 43-60% of what an event costs for any context carrying
 * attributes. Removing it means holding the encoded context and splicing it in, and there is no way to do
 * that through {@code EventOutputFormatter}: it is final, and every event kind routes its context through
 * a private method.
 * <p>
 * Summary events use the same writer and cache. Their java-core counters are package-private, so Android
 * owns an equivalent accumulator in {@link SummaryEventAccumulator}; byte-identical differential tests
 * against {@code EventOutputFormatter} keep that copy honest.
 * <p>
 * <b>What keeps it honest.</b> The field order, the conditions on optional fields and the redaction
 * directive per event kind are upstream's, and {@link JsonByteWriter} reproduces Gson's output for
 * {@code LDValue} and {@code EvaluationReason}, escaping included.
 * {@code OutboundEventBufferSerializationTest} asserts that what this writes is byte for byte what
 * {@code EventOutputFormatter} writes, over every event kind crossed with every context shape and privacy
 * setting. That test is the gate: this is allowed to be faster and nothing else.
 */
final class FullEventWriter {
    private final EventContextWriter contextWriter;
    private final ContextEncodingCache contextCache;

    /**
     * @param cacheContexts false to encode a context afresh for every event. Only a benchmark measuring
     *   what the reuse is worth has any reason to pass false.
     */
    FullEventWriter(boolean allAttributesPrivate, Collection<AttributeRef> privateAttributes, boolean cacheContexts) {
        AttributeRef[] refs = privateAttributes == null
                ? new AttributeRef[0]
                : privateAttributes.toArray(new AttributeRef[0]);
        this.contextWriter = new EventContextWriter(allAttributesPrivate, refs);
        this.contextCache = cacheContexts ? new ContextEncodingCache() : null;
    }

    /**
     * @return false if the event is not one this writes, in which case nothing has been written and the
     *   caller should fall back to {@code EventOutputFormatter}
     */
    boolean write(Event event, JsonByteWriter jw) {
        if (event.getContext() == null || !event.getContext().isValid()) {
            // Upstream skips these rather than failing, on the grounds that an event with no valid
            // context cannot be serialized at all. Same here.
            return false;
        }

        if (event instanceof Event.FeatureRequest) {
            Event.FeatureRequest fe = (Event.FeatureRequest) event;
            jw.beginObject();
            writeKindAndCreationDate(jw, fe.isDebug() ? "debug" : "feature", fe.getCreationDate());
            jw.name("key").value(fe.getKey());
            writeContext(fe.getContext(), jw, !fe.isDebug());
            if (fe.getVersion() >= 0) {
                jw.name("version").value(fe.getVersion());
            }
            if (fe.getVariation() >= 0) {
                jw.name("variation").value(fe.getVariation());
            }
            jw.nameAndValue("value", fe.getValue());
            jw.nameAndValue("default", fe.getDefaultVal());
            if (fe.getPrereqOf() != null) {
                jw.name("prereqOf").value(fe.getPrereqOf());
            }
            jw.nameAndReason("reason", fe.getReason());
            jw.endObject();
            return true;
        }

        if (event instanceof Event.Identify) {
            jw.beginObject();
            writeKindAndCreationDate(jw, "identify", event.getCreationDate());
            writeContext(event.getContext(), jw, false);
            jw.endObject();
            return true;
        }

        if (event instanceof Event.Custom) {
            Event.Custom ce = (Event.Custom) event;
            jw.beginObject();
            writeKindAndCreationDate(jw, "custom", ce.getCreationDate());
            jw.name("key").value(ce.getKey());
            // Redacting an anonymous context's attributes in a custom event is server-side behavior
            // (redactAnonymousAllEvents), and this is a client-side SDK. On the client only feature
            // events redact anonymous.
            writeContext(ce.getContext(), jw, false);
            jw.nameAndValue("data", ce.getData());
            if (ce.getMetricValue() != null) {
                jw.name("metricValue").value(ce.getMetricValue());
            }
            jw.endObject();
            return true;
        }

        return false;
    }

    /**
     * Writes one summary event in java-core's field and iteration order.
     */
    void writeSummary(SummaryEventAccumulator.Summary summary, JsonByteWriter jw) {
        jw.beginObject();
        jw.name("kind").value("summary");
        jw.name("startDate").value(summary.startDate);
        jw.name("endDate").value(summary.endDate);
        if (summary.context != null) {
            writeContext(summary.context, jw, true);
        }

        jw.name("features").beginObject();
        for (Map.Entry<String, SummaryEventAccumulator.FlagInfo> flagEntry : summary.counters.entrySet()) {
            SummaryEventAccumulator.FlagInfo flag = flagEntry.getValue();
            jw.name(flagEntry.getKey()).beginObject();
            jw.nameAndValue("default", flag.defaultValue);
            jw.name("contextKinds").beginArray();
            for (String kind : flag.contextKinds) {
                jw.value(kind);
            }
            jw.endArray();

            jw.name("counters").beginArray();
            for (int i = 0; i < flag.versionsAndVariations.size(); i++) {
                int version = flag.versionsAndVariations.keyAt(i);
                SummaryEventAccumulator.IntKeyedMap<SummaryEventAccumulator.CounterValue> variations =
                        flag.versionsAndVariations.valueAt(i);
                for (int j = 0; j < variations.size(); j++) {
                    int variation = variations.keyAt(j);
                    SummaryEventAccumulator.CounterValue counter = variations.valueAt(j);
                    jw.beginObject();
                    if (variation >= 0) {
                        jw.name("variation").value(variation);
                    }
                    if (version >= 0) {
                        jw.name("version").value(version);
                    } else {
                        jw.name("unknown").value(true);
                    }
                    jw.nameAndValue("value", counter.value);
                    jw.name("count").value(counter.count);
                    jw.endObject();
                }
            }
            jw.endArray();
            jw.endObject();
        }
        jw.endObject();
        jw.endObject();
    }

    /**
     * How many events reused an already encoded context, against how many had to encode one, as a
     * fraction. For the benchmark, so it can report the hit rate rather than assume one.
     *
     * @return the hit rate, or -1 if this writer does not cache or has encoded nothing
     */
    double contextCacheHitRate() {
        if (contextCache == null) {
            return -1;
        }
        long hits = contextCache.getHits(), misses = contextCache.getMisses();
        return hits + misses == 0 ? -1 : (double) hits / (hits + misses);
    }

    private void writeContext(LDContext context, JsonByteWriter jw, boolean redactAnonymous) {
        jw.name("context");

        if (contextCache == null) {
            contextWriter.write(context, jw, redactAnonymous);
            return;
        }

        byte[] encoded = contextCache.get(context, redactAnonymous);
        if (encoded != null) {
            jw.writeRaw(encoded);
            return;
        }
        // Written in place and copied out afterwards, so a miss costs one copy of the context's bytes
        // rather than a second writer.
        int start = jw.size();
        contextWriter.write(context, jw, redactAnonymous);
        contextCache.put(context, redactAnonymous, jw.bytesFrom(start));
    }

    private static void writeKindAndCreationDate(JsonByteWriter jw, String kind, long creationDate) {
        jw.name("kind").value(kind);
        jw.name("creationDate").value(creationDate);
    }
}
