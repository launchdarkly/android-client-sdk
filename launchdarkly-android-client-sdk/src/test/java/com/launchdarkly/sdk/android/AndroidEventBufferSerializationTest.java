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

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;

/**
 * The serialization the on-disk event log is built on: one event in, one JSON object out.
 * <p>
 * These are deliberately narrow. Everything the log does depends on a frame holding exactly one
 * event object, so the assumption is checked here rather than inferred from a delivered payload.
 */
public class AndroidEventBufferSerializationTest {
    private static final LDContext CONTEXT = LDContext.create("user-key");
    private static final String FLAG_KEY = "flag-key";

    private final LDLogAdapter logAdapter = Logs.none();

    private AndroidEventBuffer makeBuffer() {
        return new AndroidEventBuffer(100, false, Collections.emptyList(), true,
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
        AndroidEventBuffer buffer = makeBuffer();
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
    public void framesJoinIntoTheSamePayloadTheBufferWouldHaveSent() throws Exception {
        AndroidEventBuffer staged = makeBuffer();
        byte[] first = staged.serialize(new Event.Custom(1000, "first", CONTEXT, LDValue.ofNull(), null));
        byte[] second = staged.serialize(new Event.Custom(1001, "second", CONTEXT, LDValue.ofNull(), null));

        // Assembled the way the log assembles a request body out of its frames.
        String body = "[" + new String(first, Charset.forName("UTF-8")) + ","
                + new String(second, Charset.forName("UTF-8")) + "]";

        AndroidEventBuffer drained = makeBuffer();
        drained.addFullEvent(new Event.Custom(1000, "first", CONTEXT, LDValue.ofNull(), null));
        drained.addFullEvent(new Event.Custom(1001, "second", CONTEXT, LDValue.ofNull(), null));
        String expected = new String(drained.drain().getData(), Charset.forName("UTF-8"));

        assertEquals(LDValue.parse(expected), LDValue.parse(body));
    }
}
