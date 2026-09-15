package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.internal.events.AggregatedEventSummarizer;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventOutputFormatter;
import com.launchdarkly.sdk.internal.events.EventSummarizer;
import com.launchdarkly.sdk.internal.events.EventSummarizerInterface;
import com.launchdarkly.sdk.internal.events.EventsConfiguration;
import com.launchdarkly.sdk.internal.events.PerContextEventSummarizer;
import com.launchdarkly.sdk.internal.events.Sampler;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The buffer behind {@link DirectEventProcessor}: evaluations are folded into summary
 * counters as they are recorded, full-fidelity events are held in a capacity-limited list, and a
 * flush turns whatever has accumulated into a serialized payload.
 * <p>
 * The summarization and the wire format come from java-sdk-internal rather than being
 * reimplemented here, so there is one definition of what an event looks like on the wire.
 * {@code DefaultEventProcessor} is not reused along with them because it only accepts individual
 * events and summarizes them itself, on the far side of the bounded queue this is meant to get in
 * front of.
 */
final class OutboundEventBuffer {
    private static final int INITIAL_OUTPUT_BUFFER_SIZE = 2000;

    private final EventOutputFormatter formatter;
    private final EventSummarizerInterface summarizer;
    private final List<Event> events = new ArrayList<>();
    private final int capacity;
    private final LDLogger logger;
    private boolean capacityExceeded = false;
    private long droppedEventCount = 0;

    /**
     * @param capacity how many full-fidelity events may be buffered between flushes
     * @param allAttributesPrivate true to redact every context attribute except the key
     * @param privateAttributes the individual context attributes to redact
     * @param perContextSummarization true to emit one summary per context rather than one overall
     * @param logger the logger to warn on when capacity is exceeded
     */
    OutboundEventBuffer(
            int capacity,
            boolean allAttributesPrivate,
            Collection<AttributeRef> privateAttributes,
            boolean perContextSummarization,
            LDLogger logger
    ) {
        // Only the private-attribute settings affect the output; the rest of EventsConfiguration
        // describes the delivery behavior that the processor now handles itself.
        EventsConfiguration outputConfig = new EventsConfiguration(allAttributesPrivate, capacity,
                null, 0, null, null, 1, null, 0, false, false, privateAttributes,
                perContextSummarization);
        this.formatter = new EventOutputFormatter(outputConfig);
        this.summarizer = perContextSummarization
                ? new PerContextEventSummarizer()
                : new AggregatedEventSummarizer();
        this.capacity = capacity >= 0 ? capacity : 1;
        this.logger = logger;
    }

    /**
     * Folds an evaluation into the summary counters.
     * <p>
     * A counter is an aggregate rather than a buffered event, so this never drops anything and is
     * not affected by the configured capacity no matter how many evaluations an application does.
     *
     * @param event the evaluation
     */
    synchronized void summarize(Event.FeatureRequest event) {
        summarizer.summarizeEvent(
                event.getCreationDate(),
                event.getKey(),
                event.getVersion(),
                event.getVariation(),
                event.getValue(),
                event.getDefaultVal(),
                event.getContext()
        );
    }

    /**
     * Buffers an event that has to be delivered in full, subject to the configured capacity.
     *
     * @param event the event
     */
    synchronized void addFullEvent(Event event) {
        if (!Sampler.shouldSample(event.getSamplingRatio())) {
            return;
        }
        if (events.size() >= capacity) {
            if (!capacityExceeded) {
                capacityExceeded = true;
                logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
            }
            droppedEventCount++;
            return;
        }
        capacityExceeded = false;
        events.add(event);
    }

    /**
     * @return true if there is nothing buffered and no summary counters
     */
    synchronized boolean isEmpty() {
        return events.isEmpty() && summarizer.isEmpty();
    }

    /**
     * @return the number of full events dropped for capacity since this was last called
     */
    synchronized long getAndClearDroppedCount() {
        long result = droppedEventCount;
        droppedEventCount = 0;
        return result;
    }

    /**
     * Serializes everything accumulated so far and resets the buffer.
     *
     * @return the payload to send, or null if there was nothing to send
     * @throws IOException if the events could not be serialized
     */
    synchronized Payload drain() throws IOException {
        if (events.isEmpty() && summarizer.isEmpty()) {
            return null;
        }
        Event[] eventsOut = events.toArray(new Event[0]);
        List<EventSummarizer.EventSummary> summaries = summarizer.getSummariesAndReset();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(buffer, Charset.forName("UTF-8")), INITIAL_OUTPUT_BUFFER_SIZE);
        int outputEventCount;
        try {
            outputEventCount = formatter.writeOutputEvents(eventsOut, summaries, writer);
            writer.flush();
        } catch (Exception e) {
            summarizer.restoreTo(summaries);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        events.clear();
        return new Payload(buffer.toByteArray(), outputEventCount);
    }

    /**
     * A serialized batch of analytics events.
     */
    static final class Payload {
        private final byte[] data;
        private final int eventCount;

        Payload(byte[] data, int eventCount) {
            this.data = data;
            this.eventCount = eventCount;
        }

        /**
         * @return the JSON request body
         */
        byte[] getData() {
            return data;
        }

        /**
         * @return how many events the body represents, including summaries
         */
        int getEventCount() {
            return eventCount;
        }
    }
}
