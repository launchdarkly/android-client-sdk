package com.launchdarkly.sdk.android;

import com.google.gson.stream.JsonWriter;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.Event;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collection;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

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
 * <b>What it does not write.</b> Summary events still go through {@code EventOutputFormatter}. Their
 * counters live in types that are package-private in java-sdk-internal, so this could not write them even
 * if it wanted to, and it does not want to: a summary is written once per commit rather than once per
 * event, which is the wrong end of the ratio for a cache to matter.
 * <p>
 * <b>What keeps it honest.</b> The field order, the conditions on optional fields and the redaction
 * directive per event kind are upstream's, and {@code LDValue} and {@code EvaluationReason} are written
 * with upstream's own Gson serializers rather than by hand, so values cannot drift at all.
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
    boolean write(Event event, JsonWriter jw) throws IOException {
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
            writeLDValue("value", fe.getValue(), jw);
            writeLDValue("default", fe.getDefaultVal(), jw);
            if (fe.getPrereqOf() != null) {
                jw.name("prereqOf").value(fe.getPrereqOf());
            }
            writeEvaluationReason(fe.getReason(), jw);
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
            writeLDValue("data", ce.getData(), jw);
            if (ce.getMetricValue() != null) {
                jw.name("metricValue").value(ce.getMetricValue());
            }
            jw.endObject();
            return true;
        }

        return false;
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

    private void writeContext(LDContext context, JsonWriter jw, boolean redactAnonymous) throws IOException {
        jw.name("context");

        if (contextCache == null) {
            contextWriter.write(context, jw, redactAnonymous);
            return;
        }

        String encoded = contextCache.get(context, redactAnonymous);
        if (encoded == null) {
            // Encoded aside so the result can be kept. The scratch writer takes JsonWriter's defaults,
            // which the writer above has too, so these are the bytes writing straight through would have
            // produced.
            StringWriter captured = new StringWriter();
            JsonWriter scratch = new JsonWriter(captured);
            contextWriter.write(context, scratch, redactAnonymous);
            scratch.flush();
            encoded = captured.toString();
            contextCache.put(context, redactAnonymous, encoded);
        }

        jw.jsonValue(encoded);
    }

    private static void writeKindAndCreationDate(JsonWriter jw, String kind, long creationDate) throws IOException {
        jw.name("kind").value(kind);
        jw.name("creationDate").value(creationDate);
    }

    private static void writeLDValue(String key, LDValue value, JsonWriter jw) throws IOException {
        if (value == null || value.isNull()) {
            return;
        }
        jw.name(key);
        gsonInstance().toJson(value, LDValue.class, jw); // LDValue defines its own custom serializer
    }

    private static void writeEvaluationReason(EvaluationReason er, JsonWriter jw) throws IOException {
        if (er == null) {
            return;
        }
        jw.name("reason");
        gsonInstance().toJson(er, EvaluationReason.class, jw); // EvaluationReason defines its own custom serializer
    }
}
