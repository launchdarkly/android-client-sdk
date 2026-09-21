package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.internal.events.Event;
import com.google.gson.stream.JsonWriter;
import com.launchdarkly.sdk.internal.events.EventOutputFormatter;
import com.launchdarkly.sdk.internal.events.EventsConfiguration;
import com.launchdarkly.sdk.internal.events.Sampler;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The buffer behind {@link DirectEventProcessor}: evaluations are folded into summary
 * counters as they are recorded, full-fidelity events are held in a capacity-limited list, and a
 * flush turns whatever has accumulated into a serialized payload.
 * <p>
 * The event model comes from java-sdk-internal, but Android owns the wire writer and summary accumulator
 * so repeated contexts can be encoded once. Byte-identical differential tests against
 * {@link EventOutputFormatter} are the definition of compatibility. {@code DefaultEventProcessor} is not
 * reused because it only accepts individual events and summarizes them itself, on the far side of the
 * bounded queue this is meant to get in front of.
 */
final class OutboundEventBuffer {
    private static final int INITIAL_OUTPUT_BUFFER_SIZE = 2000;

    private final EventOutputFormatter formatter;
    private final FullEventWriter fullEventWriter;
    private final SummaryEventAccumulator summarizer;
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
        this(capacity, allAttributesPrivate, privateAttributes, perContextSummarization, logger, true);
    }

    /**
     * @param cacheContexts false to encode a context afresh for every event instead of reusing the
     *   encoding across a run that shares one. Only a benchmark measuring what the reuse is worth has
     *   any reason to pass false.
     */
    OutboundEventBuffer(
            int capacity,
            boolean allAttributesPrivate,
            Collection<AttributeRef> privateAttributes,
            boolean perContextSummarization,
            LDLogger logger,
            boolean cacheContexts
    ) {
        // Only the private-attribute settings affect the output; the rest of EventsConfiguration
        // describes the delivery behavior that the processor now handles itself.
        EventsConfiguration outputConfig = new EventsConfiguration(allAttributesPrivate, capacity,
                null, 0, null, null, 1, null, 0, false, false, privateAttributes,
                perContextSummarization);
        this.formatter = new EventOutputFormatter(outputConfig);
        this.fullEventWriter = new FullEventWriter(allAttributesPrivate, privateAttributes, cacheContexts);
        this.summarizer = new SummaryEventAccumulator(perContextSummarization);
        this.capacity = capacity >= 0 ? capacity : 1;
        this.logger = logger;
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
        summarizer.summarize(event);
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
     * Serializes one event into the bytes it will be sent as, for a caller that keeps events
     * somewhere other than this buffer's list.
     * <p>
     * Serializing at record time rather than at flush is what lets an event be written somewhere that
     * outlives the process: bytes can be appended to a log, an {@link Event} cannot.
     *
     * @param event the event
     * @return the event's JSON object, or null if it was dropped by sampling or could not be
     *   serialized
     */
    synchronized byte[] serialize(Event event) {
        if (!Sampler.shouldSample(event.getSamplingRatio())) {
            return null;
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), INITIAL_OUTPUT_BUFFER_SIZE);
        return writeFullEvent(event, outputStream, writer);
    }

    /**
     * Turns the evaluations counted so far into their summary events, leaving the counters empty.
     * <p>
     * Counters live only in memory, so whatever has not been summarized is what a crash takes with
     * it. Summarizing at points where the events are about to be made durable is what bounds that
     * loss, and it costs nothing in accuracy: LaunchDarkly sums the counters of every summary it
     * receives, so several summaries covering the same evaluations count the same as one.
     *
     * @return one JSON object per summary, empty if there was nothing counted
     */
    synchronized List<byte[]> serializeSummariesAndReset() {
        if (summarizer.isEmpty()) {
            return Collections.emptyList();
        }
        List<SummaryEventAccumulator.Summary> summaries = summarizer.getSummariesAndReset();
        List<byte[]> serialized = new ArrayList<>(summaries.size());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), INITIAL_OUTPUT_BUFFER_SIZE);
        for (SummaryEventAccumulator.Summary summary : summaries) {
            byte[] bytes = writeSummary(summary, outputStream, writer);
            if (bytes == null) {
                summarizer.restoreTo(summaries);
                return Collections.emptyList();
            }
            serialized.add(bytes);
        }
        return serialized;
    }

    /**
     * Serializes a run of events with one encoder instead of one per event.
     * <p>
     * Each event still becomes its own JSON object, because the store frames them individually and a
     * frame holding several objects could not be spliced into a payload. What the run shares is the
     * output stream, the writer and the UTF-8 lookup, which {@link #writeFullEvent} otherwise
     * allocates on every call -- and, because a run usually shares one context, the encoding of that
     * context too.
     *
     * @param pending the events, in the order they were recorded
     * @return one JSON object per event that survived sampling and serialized
     */
    synchronized List<byte[]> serializeAll(List<Event> pending) {
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> serialized = new ArrayList<>(pending.size());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), INITIAL_OUTPUT_BUFFER_SIZE);
        for (Event event : pending) {
            if (!Sampler.shouldSample(event.getSamplingRatio())) {
                continue;
            }
            byte[] bytes = writeFullEvent(event, outputStream, writer);
            if (bytes != null) {
                serialized.add(bytes);
            }
        }
        return serialized;
    }

    /**
     * How many events reused an already encoded context, against how many had to encode one, as a
     * fraction. For the benchmark, so it can report the hit rate rather than assume one.
     *
     * @return the hit rate, or -1 if this buffer does not cache or has encoded nothing
     */
    double contextCacheHitRate() {
        return fullEventWriter.contextCacheHitRate();
    }

    /**
     * Serializes one full event to the JSON object it is sent and stored as.
     * <p>
     * Written by {@link FullEventWriter} rather than by {@link EventOutputFormatter}, because that is
     * what lets a run of events sharing a context encode it once. Anything that writer does not claim --
     * today, any event kind this SDK does not record -- falls back to the formatter, so adding a kind
     * upstream cannot silently stop it being sent.
     *
     * @param outputStream reset before the write, so it may be shared across a run
     * @param writer must wrap {@code outputStream}, and holds nothing buffered on entry
     * @return the object's bytes, or null if the event could not be serialized
     */
    private byte[] writeFullEvent(Event event, ByteArrayOutputStream outputStream, Writer writer) {
        outputStream.reset();
        try {
            // A fresh JsonWriter per event: one refuses a second top-level value, so it cannot be
            // shared across the run the way the stream beneath it is.
            JsonWriter jsonWriter = new JsonWriter(writer);
            if (!fullEventWriter.write(event, jsonWriter)) {
                writer.flush();
                outputStream.reset();
                return writeSingleObject(new Event[]{ event }, outputStream, writer);
            }
            jsonWriter.flush();
            writer.flush();
        } catch (Exception e) {
            logger.warn("Failed to serialize an event: {}", LogValues.exceptionSummary(e));
            // Whatever the writer buffered is pushed out and thrown away with the stream, so a failed
            // event cannot bleed into the next one sharing this writer.
            try {
                writer.flush();
            } catch (Exception ignored) {
                // The stream is reset by the next call either way.
            }
            outputStream.reset();
            return null;
        }
        return outputStream.toByteArray();
    }

    private byte[] writeSummary(SummaryEventAccumulator.Summary summary,
                                ByteArrayOutputStream outputStream, Writer writer) {
        outputStream.reset();
        try {
            JsonWriter jsonWriter = new JsonWriter(writer);
            fullEventWriter.writeSummary(summary, jsonWriter);
            jsonWriter.flush();
            writer.flush();
        } catch (Exception e) {
            logger.warn("Failed to serialize an event summary: {}", LogValues.exceptionSummary(e));
            try {
                writer.flush();
            } catch (Exception ignored) {
                // The stream is reset by the next call either way.
            }
            outputStream.reset();
            return null;
        }
        return outputStream.toByteArray();
    }

    /**
     * Serializes exactly one output event and returns the JSON object on its own.
     * <p>
     * {@link EventOutputFormatter} only offers to write a whole request body, which is a JSON array,
     * and the single-event method beside it is private. So the array is written and its brackets are
     * dropped, which is sound precisely because the count it returns says how many objects are in
     * there: one object means the bytes between the brackets are that object.
     *
     * @return the object's bytes, or null if the formatter did not write exactly one event
     */
    private byte[] writeSingleObject(Event[] events) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), INITIAL_OUTPUT_BUFFER_SIZE);
        return writeSingleObject(events, outputStream, writer);
    }

    /**
     * @param outputStream reset before the write, so it may be shared across a run
     * @param writer must wrap {@code outputStream}, and holds nothing buffered on entry
     */
    private byte[] writeSingleObject(Event[] events, ByteArrayOutputStream outputStream, Writer writer) {
        outputStream.reset();
        int written;
        try {
            written = formatter.writeOutputEvents(events, Collections.emptyList(), writer);
            writer.flush();
        } catch (Exception e) {
            logger.warn("Failed to serialize an event: {}", LogValues.exceptionSummary(e));
            // Whatever the writer buffered is pushed out and thrown away with the stream, so a failed
            // event cannot bleed into the next one sharing this writer.
            try {
                writer.flush();
            } catch (Exception ignored) {
                // The stream is reset by the next call either way.
            }
            outputStream.reset();
            return null;
        }
        if (written != 1) {
            return null;
        }
        byte[] all = outputStream.toByteArray();
        if (all.length < 3 || all[0] != '[' || all[all.length - 1] != ']') {
            // Not the shape this depends on. Refusing the event is the safe reading: a frame that is
            // not one JSON object would corrupt every payload it was later spliced into.
            logger.warn("Serialized event was not the expected shape and will not be sent");
            return null;
        }
        return Arrays.copyOfRange(all, 1, all.length - 1);
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
        List<SummaryEventAccumulator.Summary> summaries = summarizer.getSummariesAndReset();

        ByteArrayOutputStream objectStream = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(objectStream, StandardCharsets.UTF_8), INITIAL_OUTPUT_BUFFER_SIZE);
        ByteArrayOutputStream payload = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        payload.write('[');
        int outputEventCount = 0;

        for (Event event : eventsOut) {
            byte[] serialized = writeFullEvent(event, objectStream, writer);
            if (serialized == null) {
                continue;
            }
            if (outputEventCount != 0) {
                payload.write(',');
            }
            payload.write(serialized);
            outputEventCount++;
        }
        for (SummaryEventAccumulator.Summary summary : summaries) {
            byte[] serialized = writeSummary(summary, objectStream, writer);
            if (serialized == null) {
                summarizer.restoreTo(summaries);
                throw new IOException("Failed to serialize an event summary");
            }
            if (outputEventCount != 0) {
                payload.write(',');
            }
            payload.write(serialized);
            outputEventCount++;
        }
        payload.write(']');
        events.clear();
        return new Payload(payload.toByteArray(), outputEventCount);
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
