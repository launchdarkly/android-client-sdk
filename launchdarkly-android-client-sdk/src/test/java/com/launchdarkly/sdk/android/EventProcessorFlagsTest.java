package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.testhelpers.httptest.HttpServer;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tests for the two per-event flags the SDK is expected to honour: {@code excludeFromSummaries},
 * which keeps an evaluation out of the counters, and {@code samplingRatio}, which decides whether a
 * full event is kept at all.
 * <p>
 * The two are enforced at different stages, because that is where each one's subject matter lives.
 * The counters are in {@link OutboundEventBuffer}, so the exclusion is checked there. The run of
 * full events and the capacity counted against it are in {@link DirectEventProcessor}, so sampling
 * is checked there, ahead of the capacity check that would otherwise record a sampled-out event as
 * a drop.
 * <p>
 * Neither flag can be reached through the SDK's own recording path. {@link DirectEventProcessor}
 * builds its feature events with the constructor overload that fixes them at {@code false} and
 * {@code 1}, so every evaluation an application performs leaves both at their defaults. That is why
 * these tests hand an event straight to the stage that enforces the flag rather than going through
 * {@code recordEvaluationEvent}: those are the only seams at which the flags can vary.
 * <p>
 * They are worth pinning even so. java-sdk-internal's {@code DefaultEventProcessor}, which this
 * path replaced, honoured both, and anything that later constructs feature events through the
 * fourteen-argument constructor — adopting sampling is the obvious candidate — makes both live at
 * once. The failure would be silent and would show up as wrong analytics rather than as an error,
 * since an event carries its sampling ratio on the wire so the receiver can scale by it.
 * <p>
 * Both ratios used here are deterministic rather than probabilistic: {@code Sampler.shouldSample}
 * short-circuits to {@code true} at 1 and to {@code false} at 0, so nothing below is flaky.
 */
public class EventProcessorFlagsTest extends EventProcessorTestBase {
    private static final String FLAG_KEY = "flags-test-flag";
    private static final int FLAG_VERSION = 3;
    private static final int VARIATION = 1;
    private static final LDValue FLAG_VALUE = LDValue.of(true);
    private static final LDValue DEFAULT_VALUE = LDValue.of(false);
    private static final long CREATION_DATE = 1_700_000_000_000L;

    // Far more than any test here records, so that capacity can never be what discarded an event.
    private static final int CAPACITY = 100;

    private static final List<Event> NO_EVENTS = Collections.emptyList();

    @Test
    public void anEvaluationExcludedFromSummariesIsNotCounted() throws IOException {
        OutboundEventBuffer buffer = makeBuffer();

        buffer.summarize(evaluation(true));

        assertNull("an excluded evaluation should leave nothing to send", buffer.drain(NO_EVENTS));
    }

    @Test
    public void anEvaluationNotExcludedFromSummariesIsCounted() throws IOException {
        OutboundEventBuffer buffer = makeBuffer();

        buffer.summarize(evaluation(false));

        assertEquals(1, summaryCountFor(drainToEvents(buffer), FLAG_KEY));
    }

    @Test
    public void excludingOneEvaluationLeavesTheOthersCounted() throws IOException {
        OutboundEventBuffer buffer = makeBuffer();

        buffer.summarize(evaluation(false));
        buffer.summarize(evaluation(true));
        buffer.summarize(evaluation(false));

        // The exclusion applies to the event that carried it and to nothing else, which is what
        // distinguishes honouring the flag from dropping the whole summary.
        assertEquals(2, summaryCountFor(drainToEvents(buffer), FLAG_KEY));
    }

    @Test
    public void aFullEventSampledOutIsNotKept() throws Exception {
        try (HttpServer server = startEventsServer()) {
            DirectEventProcessor processor = processor(server);
            try {
                processor.record(evaluation(false, 0));
                // Something the SDK will certainly send, so the flush has a payload to look at.
                // Without it there would be no request at all, and nothing to distinguish an event
                // that was dropped from one that simply has not been sent yet.
                processor.recordIdentifyEvent(CONTEXT);

                List<LDValue> events = flushAndCollect(processor, server);

                assertEquals(0, countEventsOfKind(events, "feature"));
                assertEquals(1, countEventsOfKind(events, "identify"));
            } finally {
                processor.close();
            }
        }
    }

    @Test
    public void aFullEventSampledInIsKept() throws Exception {
        try (HttpServer server = startEventsServer()) {
            DirectEventProcessor processor = processor(server);
            try {
                processor.record(evaluation(false, 1));

                assertEquals(1, countEventsOfKind(flushAndCollect(processor, server), "feature"));
            } finally {
                processor.close();
            }
        }
    }

    @Test
    public void aSampledOutEventIsNotCountedAsADrop() throws Exception {
        try (HttpServer server = startEventsServer()) {
            DirectEventProcessor processor = processor(server);
            try {
                processor.record(evaluation(false, 0));

                // Sampling and capacity are different reasons not to keep an event, and only the
                // second is a loss the SDK owes anyone a count of.
                assertEquals(0, processor.getAndClearDroppedCount());
            } finally {
                processor.close();
            }
        }
    }

    private DirectEventProcessor processor(HttpServer server) {
        return (DirectEventProcessor) makeEventProcessor(server, CAPACITY);
    }

    private OutboundEventBuffer makeBuffer() {
        return new OutboundEventBuffer(false, Collections.emptyList(), false);
    }

    private Event.FeatureRequest evaluation(boolean excludeFromSummaries) {
        return evaluation(excludeFromSummaries, 1);
    }

    private Event.FeatureRequest evaluation(boolean excludeFromSummaries, long samplingRatio) {
        return new Event.FeatureRequest(CREATION_DATE, FLAG_KEY, CONTEXT, FLAG_VERSION, VARIATION,
                FLAG_VALUE, DEFAULT_VALUE, null, null, true, null, false, samplingRatio,
                excludeFromSummaries);
    }

    private List<LDValue> drainToEvents(OutboundEventBuffer buffer) throws IOException {
        OutboundEventBuffer.Payload payload = buffer.drain(NO_EVENTS);
        if (payload == null) {
            return Collections.emptyList();
        }
        List<LDValue> events = new ArrayList<>();
        for (LDValue event : LDValue.parse(new String(payload.getData(), StandardCharsets.UTF_8))
                .values()) {
            events.add(event);
        }
        return events;
    }
}
