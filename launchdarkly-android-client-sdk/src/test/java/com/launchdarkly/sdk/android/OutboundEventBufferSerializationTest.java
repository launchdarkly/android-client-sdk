package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventOutputFormatter;
import com.launchdarkly.sdk.internal.events.EventSummarizer;
import com.launchdarkly.sdk.internal.events.EventsConfiguration;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        // Unbounded cardinality, so these stay about serialization and nothing else.
        return new OutboundEventBuffer(false, Collections.emptyList(), true, Integer.MAX_VALUE,
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
    public void theContextLimitHoldsWhateverOrderContextsArriveIn() {
        OutboundEventBuffer buffer = new OutboundEventBuffer(false, Collections.emptyList(), true, 1,
                LDLogger.withAdapter(logAdapter, ""));
        LDContext other = LDContext.create("other-key");

        assertTrue(summarize(buffer, CONTEXT));
        assertTrue(summarize(buffer, CONTEXT));
        // Equal to the one counted but a different instance, so it misses the reference check and
        // has to be found in the set rather than turned away.
        assertTrue(summarize(buffer, LDContext.create(CONTEXT.getKey())));
        assertEquals(false, summarize(buffer, other));
        assertTrue(summarize(buffer, CONTEXT));
        assertEquals(false, summarize(buffer, other));

        buffer.takeSummaries();

        // The limit lifts with the drain, for the context seen last as much as any other.
        assertTrue(summarize(buffer, other));
        assertEquals(false, summarize(buffer, CONTEXT));
    }

    private static boolean summarize(OutboundEventBuffer buffer, LDContext context) {
        return buffer.summarize(1000, FLAG_KEY, 10, 1, LDValue.of(true), LDValue.of(false), context);
    }

    @Test
    public void aSummaryTooLargeForAFrameIsSplitIntoSummariesThatFitAndCountEveryFlagOnce() {
        OutboundEventBuffer buffer = makeBuffer();
        // Long keys, so that a few thousand flags take one context's summary past what a frame holds.
        char[] padding = new char[1000];
        Arrays.fill(padding, 'k');
        String keyPrefix = new String(padding);
        int flagCount = EventLogFormat.MAX_FRAME_SIZE / padding.length * 3 / 2;
        for (int i = 0; i < flagCount; i++) {
            buffer.summarize(1000 + i, keyPrefix + i, 10, 1, LDValue.of(true), LDValue.of(false), CONTEXT);
        }

        List<byte[]> summaries = buffer.serializeSummariesAndReset();

        assertTrue("the summary was not split: " + summaries.size(), summaries.size() > 1);
        Set<String> flagKeys = new HashSet<>();
        for (byte[] serialized : summaries) {
            assertTrue("a summary is larger than a frame: " + serialized.length,
                    serialized.length <= EventLogFormat.MAX_FRAME_SIZE);
            LDValue summary = parse(serialized);
            assertEquals(LDValue.of("summary"), summary.get("kind"));
            // Each part covers the whole period, for the whole context.
            assertEquals(LDValue.of(1000), summary.get("startDate"));
            assertEquals(LDValue.of(1000 + flagCount - 1), summary.get("endDate"));
            assertEquals(LDValue.of(CONTEXT.getKey()), summary.get("context").get("key"));
            for (String flagKey : summary.get("features").keys()) {
                assertTrue("a flag was summarized twice", flagKeys.add(flagKey));
                LDValue counters = summary.get("features").get(flagKey).get("counters");
                assertEquals(1, counters.size());
                assertEquals(LDValue.of(1), counters.get(0).get("count"));
            }
        }
        assertEquals(flagCount, flagKeys.size());
    }

    @Test
    public void aSingleFlagTooLargeForAFrameIsLeftWhole() {
        OutboundEventBuffer buffer = makeBuffer();
        char[] data = new char[EventLogFormat.MAX_FRAME_SIZE];
        Arrays.fill(data, 'x');
        buffer.summarize(1000, FLAG_KEY, 10, 1, LDValue.of(new String(data)), LDValue.of(false), CONTEXT);

        List<byte[]> summaries = buffer.serializeSummariesAndReset();

        assertEquals(1, summaries.size());
        assertTrue(summaries.get(0).length > EventLogFormat.MAX_FRAME_SIZE);
    }

    @Test
    public void serializingSummariesWithNothingCountedReturnsNothing() {
        assertTrue(makeBuffer().serializeSummariesAndReset().isEmpty());
    }

    @Test
    public void framesAssembleIntoTheSamePayloadTheFormatterWritesForTheWholeRun() throws Exception {
        Event[] run = {
                new Event.Custom(1000, "first", CONTEXT, LDValue.ofNull(), null),
                new Event.Custom(1001, "second", CONTEXT, LDValue.ofNull(), null)
        };

        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (byte[] serialized : makeBuffer().serializeAll(Arrays.asList(run))) {
            EventLogFormat.writeFrame(frames, serialized);
        }
        byte[] body = EventLogFormat.assembleBody(EventLogFormat.withFileHeader(frames.toByteArray()));

        // Configured as makeBuffer() configures the formatter it wraps, so any difference is the
        // framing's and not the configuration's.
        EventOutputFormatter formatter = new EventOutputFormatter(new EventsConfiguration(false, 0,
                null, 0, null, null, 1, null, 0, false, false, Collections.emptyList(), true));
        StringWriter expected = new StringWriter();
        assertEquals(run.length, formatter.writeOutputEvents(run,
                Collections.<EventSummarizer.EventSummary>emptyList(), expected));

        assertNotNull(body);
        assertEquals(LDValue.parse(expected.toString()), parse(body));
    }
}
