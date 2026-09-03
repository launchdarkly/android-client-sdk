package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.AttributeRef;

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
 * The buffer behind the Android SDK's event processor: evaluations are folded into summary
 * counters as they are recorded, full-fidelity events are held in a capacity-limited list, and a
 * flush turns whatever has accumulated into a serialized payload.
 * <p>
 * This class is deliberately in java-sdk-internal's package rather than the Android SDK's own.
 * The summarizers, the output formatter, and the context formatter that redacts private
 * attributes are all package-private there, and there is no public way to hand java-sdk-internal
 * a summary that has already been aggregated - {@code DefaultEventProcessor} only accepts
 * individual events and summarizes them itself, on the far side of the bounded queue we are
 * trying to get in front of. Sharing the package lets the Android SDK reuse that serialization
 * instead of growing a second copy of the wire format that could drift.
 * <p>
 * Everything exposed here is a public type, so the rest of the Android SDK stays in its own
 * package and only this one file depends on the split. If java-sdk-internal ever offers this
 * capability publicly, this class should be deleted in favor of it.
 * <p>
 * It is public only so that {@code com.launchdarkly.sdk.android} can use it across the package
 * boundary; it is not SDK API and is kept out of the published Javadoc.
 *
 * @hidden
 */
public final class AndroidEventBuffer {
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
    public AndroidEventBuffer(
            int capacity,
            boolean allAttributesPrivate,
            Collection<AttributeRef> privateAttributes,
            boolean perContextSummarization,
            LDLogger logger
    ) {
        // Only the private-attribute settings affect the output; the rest of EventsConfiguration
        // describes the delivery behavior that the Android event processor now handles itself.
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
    public synchronized void summarize(Event.FeatureRequest event) {
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
    public synchronized void addFullEvent(Event event) {
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
    public synchronized boolean isEmpty() {
        return events.isEmpty() && summarizer.isEmpty();
    }

    /**
     * @return the number of full events dropped for capacity since this was last called
     */
    public synchronized long getAndClearDroppedCount() {
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
    public synchronized Payload drain() throws IOException {
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
     *
     * @hidden
     */
    public static final class Payload {
        private final byte[] data;
        private final int eventCount;

        Payload(byte[] data, int eventCount) {
            this.data = data;
            this.eventCount = eventCount;
        }

        /**
         * @return the JSON request body
         */
        public byte[] getData() {
            return data;
        }

        /**
         * @return how many events the body represents, including summaries
         */
        public int getEventCount() {
            return eventCount;
        }
    }
}
