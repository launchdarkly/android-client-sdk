package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.AggregatedEventSummarizer;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventOutputFormatter;
import com.launchdarkly.sdk.internal.events.EventSummarizer;
import com.launchdarkly.sdk.internal.events.EventSummarizerInterface;
import com.launchdarkly.sdk.internal.events.EventsConfiguration;
import com.launchdarkly.sdk.internal.events.PerContextEventSummarizer;

import org.junit.Test;

import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The serialization the on-disk event log is built on: one event in, one JSON object out.
 * <p>
 * These are deliberately narrow. Everything the log does depends on a frame holding exactly one
 * event object, so the assumption is checked here rather than inferred from a delivered payload.
 */
public class OutboundEventBufferSerializationTest {
    private static final LDContext CONTEXT = LDContext.create("user-key");
    private static final String FLAG_KEY = "flag-key";

    private final LDLogAdapter logAdapter = Logs.none();

    private OutboundEventBuffer makeBuffer() {
        return makeBuffer(false, Collections.<AttributeRef>emptyList());
    }

    private OutboundEventBuffer makeBuffer(boolean allAttributesPrivate, Collection<AttributeRef> privateAttributes) {
        return makeBuffer(allAttributesPrivate, privateAttributes, true);
    }

    private OutboundEventBuffer makeBuffer(boolean allAttributesPrivate,
                                           Collection<AttributeRef> privateAttributes,
                                           boolean perContextSummarization) {
        // Unbounded cardinality, so these stay about serialization and nothing else.
        return new OutboundEventBuffer(allAttributesPrivate, privateAttributes, perContextSummarization,
                Integer.MAX_VALUE,
                LDLogger.withAdapter(logAdapter, ""));
    }

    private LDValue parse(byte[] serialized) {
        return LDValue.parse(new String(serialized, Charset.forName("UTF-8")));
    }

    @Test
    public void customEventSerializesToASingleObject() {
        byte[] serialized = makeBuffer().serialize(
                new Event.Custom(1000, "an-event", CONTEXT, LDValue.of("data"), 2.5));

        assertNotNull(serialized);
        // The bytes are one object, with no array around it, so that a payload can be assembled by
        // joining frames with commas.
        assertEquals('{', serialized[0]);
        assertEquals('}', serialized[serialized.length - 1]);

        LDValue event = parse(serialized);
        assertEquals(LDValue.of("custom"), event.get("kind"));
        assertEquals(LDValue.of("an-event"), event.get("key"));
        assertEquals(LDValue.of(2.5), event.get("metricValue"));
    }

    @Test
    public void identifyEventSerializesToASingleObject() {
        byte[] serialized = makeBuffer().serialize(new Event.Identify(1000, CONTEXT));

        assertNotNull(serialized);
        LDValue event = parse(serialized);
        assertEquals(LDValue.of("identify"), event.get("kind"));
        assertEquals(LDValue.of(CONTEXT.getKey()), event.get("context").get("key"));
    }

    @Test
    public void featureEventSerializesToASingleObject() {
        Event.FeatureRequest event = new Event.FeatureRequest(1000, FLAG_KEY, CONTEXT, 10, 1,
                LDValue.of(true), LDValue.of(false), null, null, true, null, false);

        byte[] serialized = makeBuffer().serialize(event);

        assertNotNull(serialized);
        LDValue parsed = parse(serialized);
        assertEquals(LDValue.of("feature"), parsed.get("kind"));
        assertEquals(LDValue.of(FLAG_KEY), parsed.get("key"));
        assertEquals(LDValue.of(1), parsed.get("variation"));
    }

    @Test
    public void summariesSerializeOneObjectEachAndLeaveTheCountersEmpty() {
        OutboundEventBuffer buffer = makeBuffer();
        buffer.summarize(new Event.FeatureRequest(1000, FLAG_KEY, CONTEXT, 10, 1, LDValue.of(true),
                LDValue.of(false), null, null, false, null, false));
        buffer.summarize(new Event.FeatureRequest(1001, FLAG_KEY, CONTEXT, 10, 1, LDValue.of(true),
                LDValue.of(false), null, null, false, null, false));

        List<byte[]> summaries = buffer.serializeSummariesAndReset();

        assertEquals(1, summaries.size());
        LDValue summary = parse(summaries.get(0));
        assertEquals(LDValue.of("summary"), summary.get("kind"));
        int counted = 0;
        for (LDValue counter : summary.get("features").get(FLAG_KEY).get("counters").values()) {
            counted += counter.get("count").intValue();
        }
        assertEquals(2, counted);

        // Reset, so the same evaluations are not summarized again by the next call.
        assertTrue(buffer.serializeSummariesAndReset().isEmpty());
    }

    @Test
    public void serializingSummariesWithNothingCountedReturnsNothing() {
        assertTrue(makeBuffer().serializeSummariesAndReset().isEmpty());
    }

    @Test
    public void summariesAgreeWithFormatterAcrossContextsPrivacyAndModes() throws Exception {
        for (LDContext context : contextShapes()) {
            Event.FeatureRequest[] events = new Event.FeatureRequest[] {
                    summaryEvent(1000, "flag-a", context, 10, 1, LDValue.of(true), LDValue.of(false)),
                    summaryEvent(1001, "flag-a", context, 10, 1, LDValue.of(true), LDValue.of(false)),
                    summaryEvent(999, "flag-a", context, -1, -1, LDValue.of("fallback"), LDValue.of(false)),
                    summaryEvent(1002, "flag-b", context, 7, 0,
                            LDValue.buildObject().put("answer", 42).build(), LDValue.ofNull())
            };
            for (PrivacyShape privacy : privacyShapes()) {
                assertSummaryMatchesFormatter("per-context summary", privacy, true, events);
                assertSummaryMatchesFormatter("aggregated summary", privacy, false, events);
            }
        }
    }

    @Test
    public void summariesForSeveralContextsAgreeWithFormatter() throws Exception {
        List<LDContext> contexts = contextShapes();
        List<Event.FeatureRequest> events = new ArrayList<>();
        for (int i = 0; i < contexts.size(); i++) {
            events.add(summaryEvent(1000 + i, "flag-" + (i % 2), contexts.get(i), 10 + i, i % 3,
                    LDValue.of(i), LDValue.of(-1)));
        }
        for (PrivacyShape privacy : privacyShapes()) {
            assertSummaryMatchesFormatter("several per-context summaries", privacy, true,
                    events.toArray(new Event.FeatureRequest[0]));
            assertSummaryMatchesFormatter("one aggregated summary", privacy, false,
                    events.toArray(new Event.FeatureRequest[0]));
        }
    }

    @Test
    public void encodingFullEventsAndSummariesTogetherAgreesWithFormatter() throws Exception {
        PrivacyShape privacy = privacyShapes().get(1);
        Event.Custom custom = new Event.Custom(1003, "custom", CONTEXT, LDValue.of("data"), 1.5);
        Event.FeatureRequest evaluation = summaryEvent(
                1000, FLAG_KEY, CONTEXT, 10, 1, LDValue.of(true), LDValue.of(false));

        OutboundEventBuffer actualBuffer = makeBuffer(
                privacy.allAttributesPrivate, privacy.privateAttributes, true);
        actualBuffer.summarize(evaluation);
        String actual = new String(actualBuffer.encode(Collections.<Event>singletonList(custom),
                actualBuffer.takeSummaries()).getData(), StandardCharsets.UTF_8);

        PerContextEventSummarizer canonicalSummarizer = new PerContextEventSummarizer();
        canonicalSummarizer.summarizeEvent(
                evaluation.getCreationDate(), evaluation.getKey(), evaluation.getVersion(),
                evaluation.getVariation(), evaluation.getValue(), evaluation.getDefaultVal(),
                evaluation.getContext());
        EventsConfiguration config = new EventsConfiguration(
                privacy.allAttributesPrivate, 100, null, 0, null, null, 1, null, 0,
                false, false, privacy.privateAttributes, true);
        StringWriter expected = new StringWriter();
        new EventOutputFormatter(config).writeOutputEvents(
                new Event[] {custom}, canonicalSummarizer.getSummariesAndReset(), expected);

        assertEquals(expected.toString(), actual);
    }

    @Test
    public void framesJoinIntoTheSamePayloadTheBufferWouldHaveSent() throws Exception {
        assertMatchesFormatter("two custom events", false, Collections.<AttributeRef>emptyList(),
                new Event.Custom(1000, "first", CONTEXT, LDValue.ofNull(), null),
                new Event.Custom(1001, "second", CONTEXT, LDValue.ofNull(), null));
    }

    // MARK: Agreement with EventOutputFormatter
    //
    // Full events are written by FullEventWriter rather than by EventOutputFormatter, so that a run of
    // events sharing a context can encode it once. That is only allowed to be faster. These assert it is
    // only faster: every event kind, crossed with the context shapes and privacy settings that change what
    // redaction does, has to come out byte for byte as the formatter writes it.
    //
    // The comparison runs against the formatter itself, rather than against a recorded
    // expectation. A change upstream therefore breaks these rather than going unnoticed until the events
    // reaching LaunchDarkly disagree with every other SDK's.

    @Test
    public void everyEventKindAgreesWithTheFormatterAcrossContextsAndPrivacy() throws Exception {
        for (LDContext context : contextShapes()) {
            for (PrivacyShape privacy : privacyShapes()) {
                assertMatchesFormatter("feature", privacy, featureEvent(context, false));
                assertMatchesFormatter("debug", privacy, featureEvent(context, true));
                assertMatchesFormatter("identify", privacy, new Event.Identify(1000, context));
                assertMatchesFormatter("custom", privacy,
                        new Event.Custom(1000, "an-event", context, LDValue.of("data"), 2.5));
            }
        }
    }

    @Test
    public void anAnonymousContextWrittenBothWaysInOneRunAgreesWithTheFormatter() throws Exception {
        // The interleaving the cache keeps a second slot for. A feature event redacts this context's
        // attributes and a custom event does not, so one slot would hand each the other's encoding, and
        // the SDK would send attributes it promised to redact.
        LDContext anonymous = LDContext.builder("anon").anonymous(true)
                .set("email", "person@example.com").name("Anon").build();

        for (PrivacyShape privacy : privacyShapes()) {
            assertMatchesFormatter("anonymous, interleaved", privacy,
                    featureEvent(anonymous, false),
                    new Event.Custom(1000, "an-event", anonymous, LDValue.ofNull(), null),
                    featureEvent(anonymous, true),
                    new Event.Identify(1001, anonymous),
                    featureEvent(anonymous, false),
                    new Event.Custom(1002, "another", anonymous, LDValue.ofNull(), null));
        }
    }

    @Test
    public void alternatingContextsInOneRunAgreeWithTheFormatter() throws Exception {
        // A cache that ignored the key, or kept one entry per slot too long, would pass every test above
        // and fail this one.
        List<LDContext> shapes = contextShapes();
        List<Event> run = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (LDContext context : shapes) {
                run.add(featureEvent(context, false));
                run.add(new Event.Custom(1000 + i, "an-event", context, LDValue.ofNull(), null));
            }
        }

        assertMatchesFormatter("alternating contexts", privacyShapes().get(0), run.toArray(new Event[0]));
    }

    @Test
    public void optionalFeatureEventFieldsAgreeWithTheFormatter() throws Exception {
        PrivacyShape none = privacyShapes().get(0);

        // Each of these is a field the writer emits conditionally, and an omitted or misordered one would
        // be invisible to a test that only parses the result.
        assertMatchesFormatter("no version or variation", none,
                new Event.FeatureRequest(1000, FLAG_KEY, CONTEXT, -1, -1, LDValue.of(true),
                        LDValue.ofNull(), null, null, true, null, false));
        assertMatchesFormatter("with a reason", none,
                new Event.FeatureRequest(1000, FLAG_KEY, CONTEXT, 10, 1, LDValue.of(true),
                        LDValue.of(false), EvaluationReason.fallthrough(), null, true, null, false));
        assertMatchesFormatter("with an error reason", none,
                new Event.FeatureRequest(1000, FLAG_KEY, CONTEXT, 10, 1, LDValue.of(true),
                        LDValue.of(false), EvaluationReason.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND),
                        null, true, null, false));
        assertMatchesFormatter("with a prerequisite", none,
                new Event.FeatureRequest(1000, FLAG_KEY, CONTEXT, 10, 1, LDValue.of(true),
                        LDValue.of(false), null, "parent-flag", true, null, false));
        assertMatchesFormatter("custom with no data or metric", none,
                new Event.Custom(1000, "an-event", CONTEXT, LDValue.ofNull(), null));
        assertMatchesFormatter("custom with object data", none,
                new Event.Custom(1000, "an-event", CONTEXT,
                        LDValue.buildObject().put("a", 1).put("b", "two").build(), null));
    }

    @Test
    public void howAPrivateAttributeWasSpelledAgreesWithTheFormatter() throws Exception {
        // On Apple platforms this is the one thing that makes a context's encoding depend on something
        // outside its equality, and the cache there needs a separate check for it. Neither half of that
        // holds here -- AttributeRef compares the raw path, and EventContextWriter writes the attribute's
        // own name rather than the spelling -- and this is what says so.
        LDContext literal = LDContext.builder("userkey").name("me").set("email", "me@example.com")
                .privateAttributes(AttributeRef.fromLiteral("email")).build();
        LDContext pointer = LDContext.builder("userkey").name("me").set("email", "me@example.com")
                .privateAttributes(AttributeRef.fromPath("/email")).build();

        assertMatchesFormatter("both spellings", privacyShapes().get(0),
                new Event.Identify(1000, literal),
                new Event.Identify(1001, pointer),
                new Event.Identify(1002, literal));
    }

    @Test
    public void nestedRedactionAgreesWithTheFormatter() throws Exception {
        LDContext context = LDContext.builder("userkey")
                .set("address", LDValue.buildObject()
                        .put("street", "1 Main St")
                        .put("city", "Springfield")
                        .put("geo", LDValue.buildObject().put("lat", 1.5).put("lon", -2.5).build())
                        .build())
                .privateAttributes(AttributeRef.fromPath("/address/street"),
                        AttributeRef.fromPath("/address/geo/lat"))
                .build();

        assertMatchesFormatter("partially redacted object", privacyShapes().get(0),
                new Event.Identify(1000, context),
                featureEvent(context, false));
    }

    /**
     * Every character Gson escapes, in both of its escaping modes, and every width of UTF-8. The writer
     * encodes these itself, so this is where it would drift from Gson if it drifted at all.
     */
    @Test
    public void escapingAndEncodingAgreeWithTheFormatterByteForByte() throws Exception {
        StringBuilder controls = new StringBuilder();
        for (char c = 0; c < 0x20; c++) {
            controls.append(c);
        }
        String awkward = controls + "\"\\/<>&='" + " é中😀\u2028\u2029\u007f";
        String loneSurrogates = "high\uD83D-low\uDE00-end\uD83D";
        LDValue data = LDValue.buildObject()
                .put("plain", awkward)
                .put(awkward, LDValue.buildArray().add(awkward).add(LDValue.ofNull()).add(1).build())
                .put("dropped", LDValue.ofNull())
                .put("nested", LDValue.buildObject().put("<key>", "=value'").build())
                .build();
        LDContext context = LDContext.builder("key-" + awkward)
                .name("name-" + awkward)
                .set("attr-" + awkward, awkward)
                .set("secret<&>", "redacted")
                .set("surrogates", loneSurrogates)
                .privateAttributes(AttributeRef.fromLiteral("secret<&>"))
                .build();
        EvaluationReason[] reasons = {
                EvaluationReason.ruleMatch(3, "rule-" + awkward, true),
                EvaluationReason.prerequisiteFailed("prereq-" + awkward),
                EvaluationReason.fallthrough(true),
                EvaluationReason.error(EvaluationReason.ErrorKind.MALFORMED_FLAG),
                EvaluationReason.off().withBigSegmentsStatus(EvaluationReason.BigSegmentsStatus.STALE),
        };
        List<Event> events = new ArrayList<>();
        for (EvaluationReason reason : reasons) {
            events.add(new Event.FeatureRequest(1000, "flag-" + awkward, context, 10, 1, data,
                    LDValue.of(awkward), reason, "parent-" + awkward, true, null, false));
        }
        events.add(new Event.Custom(1001, "custom-" + awkward, context, data, 0.1));
        events.add(new Event.Custom(1002, loneSurrogates, context, LDValue.of(loneSurrogates), -0.0));
        events.add(new Event.Identify(1003, context));
        for (double number : new double[]{1.5, -0.0, 1e21, 1e-7, Integer.MAX_VALUE, 2147483648.0, -5,
                Double.NaN, Double.POSITIVE_INFINITY}) {
            events.add(new Event.Custom(1004, "number", CONTEXT, LDValue.of(number), 1e10));
        }

        for (PrivacyShape privacy : privacyShapes()) {
            assertBytesMatchFormatter(privacy, events.toArray(new Event[0]));
        }
    }

    /**
     * Gson.toJson turns serializeNulls off, so a null reached through an LDValue or a reason drops its
     * name too, while an array element that is null is still written. That holds on the partial-redaction
     * path as well, where the context writer names each member of an object attribute itself.
     */
    @Test
    public void nullsInsideValuesAndReasonsAgreeWithTheFormatter() throws Exception {
        LDValue withNulls = LDValue.buildObject()
                .put("city", "Springfield")
                .put("gone", LDValue.ofNull())
                .put("list", LDValue.buildArray().add(LDValue.ofNull()).build())
                .put("inner", LDValue.buildObject().put("gone", LDValue.ofNull()).put("kept", 1).build())
                .build();
        LDContext context = LDContext.builder("nulls").set("address", withNulls).build();
        List<Event> events = new ArrayList<>();
        events.add(new Event.FeatureRequest(1000, FLAG_KEY, context, 10, 1, withNulls, LDValue.ofNull(),
                EvaluationReason.prerequisiteFailed(null), null, true, null, false));
        events.add(new Event.Custom(1001, "custom", context, withNulls, null));
        events.add(new Event.Identify(1002, context));

        for (PrivacyShape privacy : privacyShapes()) {
            assertBytesMatchFormatter(privacy, events.toArray(new Event[0]));
        }
    }

    @Test
    public void escapingInSummariesAgreesWithTheFormatter() throws Exception {
        String awkward = "\u0001\t\"\\<>&='é中😀\u2028";
        LDContext context = LDContext.builder("key" + awkward).set("attr" + awkward, awkward).build();
        for (PrivacyShape privacy : privacyShapes()) {
            assertSummaryMatchesFormatter("awkward strings", privacy, true,
                    summaryEvent(1000, "flag" + awkward, context, 10, 1, LDValue.of(awkward), LDValue.of(awkward)),
                    summaryEvent(1001, "flag" + awkward, context, -1, -1, LDValue.of(2.5), LDValue.ofNull()));
        }
    }

    /**
     * As {@link #assertMatchesFormatter}, but compared as the bytes that go on the wire: the formatter's
     * characters through the same UTF-8 encoder the SDK used to write them with.
     */
    private void assertBytesMatchFormatter(PrivacyShape privacy, Event... events) throws Exception {
        OutboundEventBuffer staged = makeBuffer(privacy.allAttributesPrivate, privacy.privateAttributes);
        java.io.ByteArrayOutputStream actual = new java.io.ByteArrayOutputStream();
        actual.write('[');
        for (int i = 0; i < events.length; i++) {
            byte[] frame = staged.serialize(events[i]);
            assertNotNull("event " + i + " did not serialize", frame);
            if (i > 0) {
                actual.write(',');
            }
            actual.write(frame);
        }
        actual.write(']');

        EventsConfiguration config = new EventsConfiguration(
                privacy.allAttributesPrivate, 100, null, 0, null, null, 1, null, 0,
                false, false, privacy.privateAttributes, true);
        java.io.ByteArrayOutputStream expected = new java.io.ByteArrayOutputStream();
        java.io.Writer writer = new java.io.OutputStreamWriter(expected, StandardCharsets.UTF_8);
        new EventOutputFormatter(config).writeOutputEvents(
                events, Collections.<EventSummarizer.EventSummary>emptyList(), writer);
        writer.flush();

        assertEquals(new String(expected.toByteArray(), StandardCharsets.ISO_8859_1),
                new String(actual.toByteArray(), StandardCharsets.ISO_8859_1));
    }

    /**
     * Asserts that serializing each event on its own and joining the frames produces exactly the payload
     * {@link EventOutputFormatter} would have sent for the same events.
     * <p>
     * Compared as bytes rather than as parsed JSON. Two payloads that parse alike but order their fields
     * differently would be a drift from every other SDK's output, and parsing is exactly what would hide
     * it.
     */
    private void assertMatchesFormatter(String what, PrivacyShape privacy, Event... events) throws Exception {
        assertMatchesFormatter(what, privacy.allAttributesPrivate, privacy.privateAttributes, events);
    }

    private void assertMatchesFormatter(String what, boolean allAttributesPrivate,
                                        Collection<AttributeRef> privateAttributes, Event... events) throws Exception {
        OutboundEventBuffer staged = makeBuffer(allAttributesPrivate, privateAttributes);
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < events.length; i++) {
            byte[] frame = staged.serialize(events[i]);
            assertNotNull(what + ": event " + i + " did not serialize", frame);
            body.append(i == 0 ? "" : ",").append(new String(frame, StandardCharsets.UTF_8));
        }
        body.append(']');

        EventsConfiguration config = new EventsConfiguration(
                allAttributesPrivate, 100, null, 0, null, null, 1, null, 0,
                false, false, privateAttributes, true);
        StringWriter expected = new StringWriter();
        new EventOutputFormatter(config).writeOutputEvents(
                events, Collections.<EventSummarizer.EventSummary>emptyList(), expected);

        assertEquals(what, expected.toString(), body.toString());
    }

    private void assertSummaryMatchesFormatter(String what, PrivacyShape privacy,
                                               boolean perContextSummarization,
                                               Event.FeatureRequest... events) throws Exception {
        OutboundEventBuffer actualBuffer = makeBuffer(
                privacy.allAttributesPrivate, privacy.privateAttributes, perContextSummarization);
        EventSummarizerInterface canonicalSummarizer = perContextSummarization
                ? new PerContextEventSummarizer()
                : new AggregatedEventSummarizer();

        for (Event.FeatureRequest event : events) {
            actualBuffer.summarize(event);
            canonicalSummarizer.summarizeEvent(
                    event.getCreationDate(), event.getKey(), event.getVersion(), event.getVariation(),
                    event.getValue(), event.getDefaultVal(), event.getContext());
        }

        StringBuilder actual = new StringBuilder("[");
        List<byte[]> frames = actualBuffer.serializeSummariesAndReset();
        for (int i = 0; i < frames.size(); i++) {
            actual.append(i == 0 ? "" : ",").append(new String(frames.get(i), StandardCharsets.UTF_8));
        }
        actual.append(']');

        EventsConfiguration config = new EventsConfiguration(
                privacy.allAttributesPrivate, 100, null, 0, null, null, 1, null, 0,
                false, false, privacy.privateAttributes, perContextSummarization);
        EventOutputFormatter formatter = new EventOutputFormatter(config);
        List<EventSummarizer.EventSummary> summaries = canonicalSummarizer.getSummariesAndReset();
        StringWriter expected = new StringWriter();
        formatter.writeOutputEvents(new Event[0], summaries, expected);

        assertEquals(what, expected.toString(), actual.toString());
    }

    /**
     * Also asserts the run path, which encodes the whole run with one writer and is what a commit
     * actually uses. It shares the cache across the run in a way serializing one at a time does not.
     */
    @Test
    public void serializingARunAgreesWithSerializingOneAtATime() {
        for (LDContext context : contextShapes()) {
            for (PrivacyShape privacy : privacyShapes()) {
                List<Event> run = Arrays.<Event>asList(
                        featureEvent(context, false),
                        new Event.Custom(1000, "an-event", context, LDValue.ofNull(), null),
                        featureEvent(context, true),
                        new Event.Identify(1001, context));

                OutboundEventBuffer singly = makeBuffer(privacy.allAttributesPrivate, privacy.privateAttributes);
                List<String> one = new ArrayList<>();
                for (Event event : run) {
                    one.add(new String(singly.serialize(event), StandardCharsets.UTF_8));
                }

                OutboundEventBuffer batched = makeBuffer(privacy.allAttributesPrivate, privacy.privateAttributes);
                List<String> many = new ArrayList<>();
                for (byte[] frame : batched.serializeAll(run)) {
                    many.add(new String(frame, StandardCharsets.UTF_8));
                }

                assertEquals(one, many);
            }
        }
    }

    private static Event.FeatureRequest featureEvent(LDContext context, boolean debug) {
        Event.FeatureRequest event = new Event.FeatureRequest(1000, FLAG_KEY, context, 10, 1,
                LDValue.of(true), LDValue.of(false), null, null, true, debug ? Long.MAX_VALUE : null, false);
        return debug ? event.toDebugEvent() : event;
    }

    private static Event.FeatureRequest summaryEvent(long timestamp, String key, LDContext context,
                                                     int version, int variation, LDValue value,
                                                     LDValue defaultValue) {
        return new Event.FeatureRequest(timestamp, key, context, version, variation, value, defaultValue,
                null, null, false, null, false);
    }

    private static List<LDContext> contextShapes() {
        return Arrays.asList(
                LDContext.create("key-only"),
                LDContext.builder("named").name("Named").set("email", "person@example.com")
                        .set("age", 36).set("verified", true).build(),
                LDContext.builder("anon").anonymous(true).name("Anon")
                        .set("email", "person@example.com").build(),
                LDContext.builder("nested")
                        .set("address", LDValue.buildObject().put("city", "Springfield").build())
                        .set("tags", LDValue.buildArray().add("a").add("b").build()).build(),
                LDContext.createMulti(
                        LDContext.builder("user-key").name("User").set("email", "person@example.com").build(),
                        LDContext.builder(ContextKind.of("device"), "device-key").anonymous(true)
                                .set("os", LDValue.buildObject().put("name", "Android").build()).build()),
                LDContext.builder("per-context-private").name("Private").set("email", "person@example.com")
                        .privateAttributes(AttributeRef.fromLiteral("email")).build());
    }

    private static final class PrivacyShape {
        final boolean allAttributesPrivate;
        final Collection<AttributeRef> privateAttributes;

        PrivacyShape(boolean allAttributesPrivate, Collection<AttributeRef> privateAttributes) {
            this.allAttributesPrivate = allAttributesPrivate;
            this.privateAttributes = privateAttributes;
        }
    }

    private static List<PrivacyShape> privacyShapes() {
        return Arrays.asList(
                new PrivacyShape(false, Collections.<AttributeRef>emptyList()),
                new PrivacyShape(false, Collections.singletonList(AttributeRef.fromLiteral("email"))),
                new PrivacyShape(false, Collections.singletonList(AttributeRef.fromPath("/address/city"))),
                new PrivacyShape(true, Collections.<AttributeRef>emptyList()));
    }
}
