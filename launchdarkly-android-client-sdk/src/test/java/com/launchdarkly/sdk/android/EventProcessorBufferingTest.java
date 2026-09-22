package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.testhelpers.httptest.HttpServer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for what the event processor is allowed to drop when flag evaluations arrive faster than
 * it can consume them.
 * <p>
 * The processor used to be a thin wrapper around java-sdk-internal's
 * {@code DefaultEventProcessor}, which buffers in two stages: every recorded event is first
 * offered to a bounded "inbox" queue, and only after a background dispatcher thread picks it up is
 * it either summarized or placed in the outbox that a flush actually drains. The inbox is sized to
 * the configured event capacity, and it carries the processor's own FLUSH and SHUTDOWN control
 * messages as well as events. So a burst of evaluations could fill it and cause silent drops even
 * though those evaluations were only ever going to contribute a counter to a summary event, and
 * even though unrelated custom events were competing for the same slots.
 * <p>
 * These tests pin the behavior we want instead: summarization happens when the evaluation is
 * recorded, so an evaluation never occupies a queue slot, and capacity only ever limits
 * full-fidelity events. With summarization ahead of buffering there is no queue in this path, so
 * the counts asserted below are exact rather than approximate.
 */
public class EventProcessorBufferingTest extends EventProcessorTestBase {
    private static final String FLAG_KEY = "burst-flag";
    private static final int FLAG_VERSION = 10;
    private static final int VARIATION = 1;
    private static final LDValue FLAG_VALUE = LDValue.of(true);
    private static final LDValue DEFAULT_VALUE = LDValue.of(false);

    // Deliberately far smaller than the number of evaluations each test records, so that any
    // capacity-limited queue in the evaluation path would be guaranteed to overflow.
    private static final int CAPACITY = 100;

    private static final int BURST_THREADS = 4;
    private static final int EVALUATIONS_PER_THREAD = 25_000;
    private static final int TOTAL_EVALUATIONS = BURST_THREADS * EVALUATIONS_PER_THREAD;

    // How many full-fidelity events the interleaving tests space through the burst. Comfortably
    // under CAPACITY, so the buffer is never legitimately entitled to drop any of them.
    private static final int INTERLEAVED_EVENTS = 50;

    @Test
    public void summaryCountsSurviveEvaluationBurst() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, CAPACITY);
            try {
                new EvaluationBurst(eventProcessor).join();

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                // Every evaluation contributes to the summary regardless of capacity, because a
                // summary counter is not a buffered event.
                assertEquals(TOTAL_EVALUATIONS, summaryCountFor(events, FLAG_KEY));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void customEventsSurviveEvaluationBurst() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, CAPACITY);
            try {
                // Record custom events spaced evenly through the burst. These are full-fidelity
                // events and there are far fewer of them than the configured capacity, so none of
                // them may be lost no matter how many evaluations are happening alongside them.
                EvaluationBurst burst = new EvaluationBurst(eventProcessor);
                for (int i = 1; i <= INTERLEAVED_EVENTS; i++) {
                    burst.awaitFraction(i, INTERLEAVED_EVENTS + 1);
                    eventProcessor.recordCustomEvent(CONTEXT, "burst-custom", LDValue.ofNull(), null);
                }
                burst.join();

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(INTERLEAVED_EVENTS, countEventsOfKind(events, "custom"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void fullFidelityEvaluationEventsSurviveEvaluationBurst() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, CAPACITY);
            try {
                // A flag with trackEvents on produces a full feature event in addition to its
                // summary counter. Evaluations of untracked flags must not crowd these out.
                EvaluationBurst burst = new EvaluationBurst(eventProcessor);
                for (int i = 1; i <= INTERLEAVED_EVENTS; i++) {
                    burst.awaitFraction(i, INTERLEAVED_EVENTS + 1);
                    eventProcessor.recordEvaluationEvent(CONTEXT, "tracked-flag", FLAG_VERSION,
                            VARIATION, FLAG_VALUE, null, DEFAULT_VALUE, true, null);
                }
                burst.join();

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(INTERLEAVED_EVENTS, countEventsOfKind(events, "feature"));
                // The tracked evaluations are summarized too, alongside the burst.
                assertEquals(INTERLEAVED_EVENTS, summaryCountFor(events, "tracked-flag"));
                assertEquals(TOTAL_EVALUATIONS, summaryCountFor(events, FLAG_KEY));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void distinctSummarizedContextsAreBoundedByCapacity() throws Exception {
        // A summary counter costs a retained context and a set of counters per distinct context, and
        // nothing drains it while the client is offline. Capacity has to bound that, or an application
        // that goes on identifying through an outage grows the summarizer for as long as it lasts.
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, CAPACITY);
            try {
                for (int i = 0; i < CAPACITY * 3; i++) {
                    eventProcessor.recordEvaluationEvent(contextNumber(i), FLAG_KEY, FLAG_VERSION,
                            VARIATION, FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                }

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                // One summary event per context that was counted, so this is the cardinality the
                // summarizer was holding.
                assertEquals(CAPACITY, countEventsOfKind(events, "summary"));
                logging.assertWarnLogged("Exceeded the number of contexts");
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aContextAlreadyBeingCountedKeepsCountingOnceTheLimitIsReached() throws Exception {
        // The limit is on how many contexts are held, not on how many evaluations are counted. An
        // application evaluating against one context must not start losing counts because some other
        // part of it churned through contexts.
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, CAPACITY);
            try {
                eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION,
                        FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                for (int i = 0; i < CAPACITY * 3; i++) {
                    eventProcessor.recordEvaluationEvent(contextNumber(i), FLAG_KEY, FLAG_VERSION,
                            VARIATION, FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                }
                int evaluationsAfterTheLimit = 500;
                for (int i = 0; i < evaluationsAfterTheLimit; i++) {
                    eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION,
                            FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                }

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(evaluationsAfterTheLimit + 1, summaryCountForContext(events, CONTEXT));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void theLimitLiftsOnceTheSummariesHaveBeenDelivered() throws Exception {
        // The bound is on how many contexts are held at once, not on how many an application may ever
        // use, so a delivery has to make room for the next set.
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, CAPACITY);
            try {
                for (int i = 0; i < CAPACITY * 3; i++) {
                    eventProcessor.recordEvaluationEvent(contextNumber(i), FLAG_KEY, FLAG_VERSION,
                            VARIATION, FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                }
                flushAndCollect(eventProcessor, server);

                for (int i = CAPACITY * 3; i < CAPACITY * 4; i++) {
                    eventProcessor.recordEvaluationEvent(contextNumber(i), FLAG_KEY, FLAG_VERSION,
                            VARIATION, FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                }
                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(CAPACITY, countEventsOfKind(events, "summary"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aCommitPointMakesRoomInTheSummarizer() throws Exception {
        // Counters are materialized into summary events and reset at every commit point, so the
        // summarizer only ever holds the contexts seen since the last one. That reset is what keeps
        // the cardinality limit from binding: without it an application identifying its way through
        // more contexts than capacity would start losing counts partway through.
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, CAPACITY);
            try {
                int contexts = CAPACITY * 3;
                for (int i = 0; i < contexts; i++) {
                    LDContext context = contextNumber(i);
                    eventProcessor.recordEvaluationEvent(context, FLAG_KEY, FLAG_VERSION, VARIATION,
                            FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                    eventProcessor.recordIdentifyEvent(context); // a commit point
                }

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(contexts, countEventsOfKind(events, "summary"));
                assertFalse("evaluations were turned away even though every one of them followed a"
                                + " commit point that should have made room",
                        logged("Exceeded the number of contexts"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    private boolean logged(String messageSubstring) {
        for (String message : logging.logCapture.getMessageStrings()) {
            if (message.contains(messageSubstring)) {
                return true;
            }
        }
        return false;
    }

    private static LDContext contextNumber(int i) {
        return LDContext.builder("burst-user-" + i).set("email", "user" + i + "@example.com").build();
    }

    /** @return how many evaluations the summary for this context counted, across every flag in it */
    private static int summaryCountForContext(List<LDValue> events, LDContext context) {
        int total = 0;
        for (LDValue event : events) {
            if (!"summary".equals(event.get("kind").stringValue())
                    || !context.getKey().equals(event.get("context").get("key").stringValue())) {
                continue;
            }
            LDValue features = event.get("features");
            for (String flagKey : features.keys()) {
                for (LDValue counter : features.get(flagKey).get("counters").values()) {
                    total += counter.get("count").intValue();
                }
            }
        }
        return total;
    }

    /**
     * Records evaluations of an untracked flag from several threads at once, faster than any
     * single consumer thread could drain them. Tests that need to interleave other events use
     * {@link #awaitFraction} rather than sleeping, so the interleaved events land at the same
     * points in the burst regardless of how fast the machine running the test is.
     */
    private final class EvaluationBurst {
        private final List<Thread> workers = new ArrayList<>();
        private volatile int progress;

        EvaluationBurst(EventProcessor eventProcessor) {
            for (int i = 0; i < BURST_THREADS; i++) {
                boolean reportsProgress = i == 0;
                Thread worker = new Thread(() -> {
                    for (int j = 0; j < EVALUATIONS_PER_THREAD; j++) {
                        eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION,
                                VARIATION, FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                        if (reportsProgress) {
                            progress = j + 1;
                        }
                    }
                });
                worker.start();
                workers.add(worker);
            }
        }

        /** Blocks until the burst is {@code numerator/denominator} of the way through. */
        void awaitFraction(int numerator, int denominator) {
            int target = (int) ((long) EVALUATIONS_PER_THREAD * numerator / denominator);
            while (progress < target && workers.get(0).isAlive()) {
                Thread.yield();
            }
        }

        void join() throws InterruptedException {
            for (Thread worker : workers) {
                worker.join();
            }
        }
    }
}
