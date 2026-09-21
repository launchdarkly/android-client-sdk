package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.internal.events.AggregatedEventSummarizer;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventOutputFormatter;
import com.launchdarkly.sdk.internal.events.EventSummarizer;
import com.launchdarkly.sdk.internal.events.EventSummarizerInterface;
import com.launchdarkly.sdk.internal.events.EventsConfiguration;
import com.launchdarkly.sdk.internal.events.PerContextEventSummarizer;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * The counters and the encoder behind {@link DirectEventProcessor}: evaluations are folded into
 * summary counters as they are recorded, and a flush turns a run of full-fidelity events, together
 * with the counters accumulated beside them, into the payload they will be sent as.
 * <p>
 * The full events are held by the processor rather than here. Capacity is counted once, against
 * everything the SDK is holding, and the processor is the only place that can see all of it.
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

    /**
     * @param allAttributesPrivate true to redact every context attribute except the key
     * @param privateAttributes the individual context attributes to redact
     * @param perContextSummarization true to emit one summary per context rather than one overall
     */
    OutboundEventBuffer(
            boolean allAttributesPrivate,
            Collection<AttributeRef> privateAttributes,
            boolean perContextSummarization
    ) {
        // Only the private-attribute settings affect the output; the rest of EventsConfiguration
        // describes the delivery behavior that the processor now handles itself, capacity included.
        EventsConfiguration outputConfig = new EventsConfiguration(allAttributesPrivate, 0,
                null, 0, null, null, 1, null, 0, false, false, privateAttributes,
                perContextSummarization);
        this.formatter = new EventOutputFormatter(outputConfig);
        this.summarizer = perContextSummarization
                ? new PerContextEventSummarizer()
                : new AggregatedEventSummarizer();
    }

    /**
     * Folds an evaluation into the summary counters, unless the evaluation asked to be left out of
     * them.
     * <p>
     * A counter is an aggregate rather than a buffered event, so this never drops anything and is
     * not affected by the configured capacity no matter how many evaluations an application does.
     *
     * @param event the evaluation
     */
    synchronized void summarize(Event.FeatureRequest event) {
        // Checked here rather than in DirectEventProcessor, for the same reason the sampling ratio
        // is: this is where an event arrives from outside. The processor builds its own through the
        // constructor overload that leaves this false, so a guard there could never fire and would
        // read as dead. java-sdk-internal's DefaultEventProcessor, which this path replaced, honored
        // the flag, and a counter is the one thing no later stage can reconstruct.
        if (event.isExcludeFromSummaries()) {
            return;
        }
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
     * Serializes a run of events, together with the evaluations counted beside it, into the payload
     * they will be sent as.
     * <p>
     * The counters are taken under the lock and the encode happens outside it, so a thread recording
     * an event waits only for a handful of reference swaps and never for the encoder. Recording
     * happens on whichever thread evaluated a flag, which on Android is usually the main one, and
     * the encode is the dominant cost on this path.
     * <p>
     * A run that cannot be serialized is lost rather than put back. Everything the encoder can fail
     * on is a property of the data it was handed -- the output stream is a byte array and cannot fail
     * transiently -- so a failed run would fail again on every later flush, and nothing would ever
     * be delivered again.
     *
     * @param run the full events to send, in the order they were recorded
     * @return the payload to send, or null if there was nothing to send
     * @throws IOException if the events could not be serialized
     */
    Payload drain(List<Event> run) throws IOException {
        List<EventSummarizer.EventSummary> summaries;
        synchronized (this) {
            if (run.isEmpty() && summarizer.isEmpty()) {
                return null;
            }
            summaries = summarizer.getSummariesAndReset();
        }

        Event[] eventsOut = run.toArray(new Event[0]);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(buffer, StandardCharsets.UTF_8), INITIAL_OUTPUT_BUFFER_SIZE);
        int outputEventCount;
        try {
            outputEventCount = formatter.writeOutputEvents(eventsOut, summaries, writer);
            writer.flush();
        } catch (Exception e) {
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
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
