package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.LDContext;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final Event[] NO_EVENTS = new Event[0];
    private static final List<EventSummarizer.EventSummary> NO_SUMMARIES = Collections.emptyList();

    private final EventOutputFormatter formatter;
    private final EventSummarizerInterface summarizer;
    private final LDLogger logger;

    /**
     * How many distinct contexts may be counted between two drains, and which ones already are.
     * <p>
     * The per-context summarizer keeps a counter set per context, keyed on the whole context with
     * every attribute retained, and offers no way to ask how many it holds. Without this the only
     * bound on it is how long the SDK goes without a drain -- and it is not drained at all while the
     * client is offline, which is exactly when an application is free to go on evaluating.
     */
    private final int maxContexts;
    private final Set<LDContext> countedContexts;

    /**
     * @param allAttributesPrivate true to redact every context attribute except the key
     * @param privateAttributes the individual context attributes to redact
     * @param perContextSummarization true to emit one summary per context rather than one overall
     * @param maxContexts how many distinct contexts may be counted between two drains
     * @param logger where to report an event that cannot be serialized
     */
    OutboundEventBuffer(
            boolean allAttributesPrivate,
            Collection<AttributeRef> privateAttributes,
            boolean perContextSummarization,
            int maxContexts,
            LDLogger logger
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
        // One bucket overall, so there is no cardinality to bound and nothing to track it with.
        this.maxContexts = perContextSummarization ? maxContexts : Integer.MAX_VALUE;
        this.countedContexts = perContextSummarization ? new HashSet<>() : null;
        this.logger = logger;
    }

    /**
     * Folds an evaluation into the summary counters, unless the evaluation asked to be left out of
     * them.
     * <p>
     * A counter is an aggregate rather than a buffered event, so no number of evaluations of a context
     * already being counted can make this drop anything. What capacity does bound is how many distinct
     * contexts are counted at once, because each one costs a retained context and its own counters.
     *
     * @param event the evaluation
     * @return false if this evaluation was not counted, because counting it would have meant holding
     *   a context beyond the configured capacity
     */
    synchronized boolean summarize(Event.FeatureRequest event) {
        // Checked here rather than in DirectEventProcessor, for the same reason the sampling ratio
        // is: this is where an event arrives from outside. The processor builds its own through the
        // constructor overload that leaves this false, so a guard there could never fire and would
        // read as dead. java-sdk-internal's DefaultEventProcessor, which this path replaced, honored
        // the flag, and a counter is the one thing no later stage can reconstruct.
        if (event.isExcludeFromSummaries()) {
            return true;
        }
        // Only a context that is not being counted yet can be turned away, so reaching the limit costs
        // an application evaluating against one context nothing, however many evaluations it does.
        if (countedContexts != null && !countedContexts.contains(event.getContext())) {
            if (countedContexts.size() >= maxContexts) {
                return false;
            }
            countedContexts.add(event.getContext());
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
        return true;
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
     * A run that cannot be serialized as a whole is retried per event and per summary. Anything that
     * still fails is dropped and logged; the rest is sent. Everything the encoder can fail on is a
     * property of the data it was handed -- the output stream is a byte array and cannot fail
     * transiently -- so putting a failed item back would only make every later flush fail too.
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
            if (countedContexts != null) {
                countedContexts.clear();
            }
        }

        try {
            return encodeAll(run, summaries);
        } catch (Exception e) {
            logger.error("Dropping unserializable analytics event(s): {}",
                    LogValues.exceptionSummary(e));
            logger.debug("{}", LogValues.exceptionTrace(e));
            return encodeSkippingFailures(run, summaries);
        }
    }

    private Payload encodeAll(List<Event> run, List<EventSummarizer.EventSummary> summaries)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        int outputEventCount = write(run.toArray(NO_EVENTS), summaries, buffer);
        if (outputEventCount == 0) {
            return null;
        }
        return new Payload(buffer.toByteArray(), outputEventCount);
    }

    private Payload encodeSkippingFailures(List<Event> run,
                                           List<EventSummarizer.EventSummary> summaries) {
        List<byte[]> objects = new ArrayList<>();
        int outputEventCount = 0;
        for (Event event : run) {
            EncodedPiece piece = tryEncode(new Event[] { event }, NO_SUMMARIES);
            if (piece == null) {
                logger.error("Dropping unserializable event of type {}", event.getClass().getSimpleName());
                continue;
            }
            objects.add(piece.jsonObject);
            outputEventCount += piece.eventCount;
        }
        for (EventSummarizer.EventSummary summary : summaries) {
            EncodedPiece piece = tryEncode(NO_EVENTS, Collections.singletonList(summary));
            if (piece == null) {
                logger.error("Dropping unserializable summary event");
                continue;
            }
            objects.add(piece.jsonObject);
            outputEventCount += piece.eventCount;
        }
        if (objects.isEmpty()) {
            return null;
        }
        return new Payload(joinObjects(objects), outputEventCount);
    }

    private EncodedPiece tryEncode(Event[] events, List<EventSummarizer.EventSummary> summaries) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
            int count = write(events, summaries, buffer);
            if (count == 0) {
                return null;
            }
            byte[] jsonObject = objectFromArray(buffer.toByteArray());
            if (jsonObject == null) {
                return null;
            }
            return new EncodedPiece(jsonObject, count);
        } catch (Exception e) {
            logger.debug("{}", LogValues.exceptionTrace(e));
            return null;
        }
    }

    private int write(Event[] events, List<EventSummarizer.EventSummary> summaries,
                      ByteArrayOutputStream buffer) throws IOException {
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(buffer, StandardCharsets.UTF_8), INITIAL_OUTPUT_BUFFER_SIZE);
        int outputEventCount = formatter.writeOutputEvents(events, summaries, writer);
        writer.flush();
        return outputEventCount;
    }

    /**
     * {@code EventOutputFormatter} always writes a JSON array. For a single successful event that
     * is {@code [{...}]}, and the payload we are assembling needs the object in the middle.
     */
    static byte[] objectFromArray(byte[] arrayJson) {
        int start = 0;
        int end = arrayJson.length - 1;
        while (start <= end && arrayJson[start] <= ' ') {
            start++;
        }
        while (end >= start && arrayJson[end] <= ' ') {
            end--;
        }
        if (start > end || arrayJson[start] != '[' || arrayJson[end] != ']') {
            return null;
        }
        start++;
        end--;
        while (start <= end && arrayJson[start] <= ' ') {
            start++;
        }
        while (end >= start && arrayJson[end] <= ' ') {
            end--;
        }
        if (start > end || arrayJson[start] != '{') {
            return null;
        }
        int length = end - start + 1;
        byte[] object = new byte[length];
        System.arraycopy(arrayJson, start, object, 0, length);
        return object;
    }

    private static byte[] joinObjects(List<byte[]> objects) {
        int size = 2;
        for (int i = 0; i < objects.size(); i++) {
            if (i > 0) {
                size++;
            }
            size += objects.get(i).length;
        }
        byte[] out = new byte[size];
        int offset = 0;
        out[offset++] = '[';
        for (int i = 0; i < objects.size(); i++) {
            if (i > 0) {
                out[offset++] = ',';
            }
            byte[] object = objects.get(i);
            System.arraycopy(object, 0, out, offset, object.length);
            offset += object.length;
        }
        out[offset] = ']';
        return out;
    }

    private static final class EncodedPiece {
        final byte[] jsonObject;
        final int eventCount;

        EncodedPiece(byte[] jsonObject, int eventCount) {
            this.jsonObject = jsonObject;
            this.eventCount = eventCount;
        }
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
