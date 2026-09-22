package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
    private static final Event[] NO_EVENTS = new Event[0];
    private static final List<EventSummarizer.EventSummary> NO_SUMMARIES = Collections.emptyList();

    private final EventOutputFormatter formatter;
    private final EventSummarizerInterface summarizer;
    private final LDLogger logger;

    /**
     * @param allAttributesPrivate true to redact every context attribute except the key
     * @param privateAttributes the individual context attributes to redact
     * @param perContextSummarization true to emit one summary per context rather than one overall
     * @param logger where to report an event that cannot be serialized
     */
    OutboundEventBuffer(
            boolean allAttributesPrivate,
            Collection<AttributeRef> privateAttributes,
            boolean perContextSummarization,
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
     * Serializes one event into the bytes it will be sent as, for a caller that keeps events
     * somewhere other than this buffer's list.
     * <p>
     * Serializing at record time rather than at flush is what lets an event be written somewhere that
     * outlives the process: bytes can be appended to a log, an {@link Event} cannot.
     * <p>
     * Deliberately not synchronized. It reads the formatter and writes only locals, so it shares
     * nothing with a thread recording an event, and taking the lock would make an encode wait on a
     * counter increment and the other way round.
     *
     * @param event the event
     * @return the event's JSON object, or null if it was dropped by sampling or could not be
     *   serialized
     */
    byte[] serialize(Event event) {
        if (!Sampler.shouldSample(event.getSamplingRatio())) {
            return null;
        }
        return writeSingleObject(new Event[]{ event }, Collections.<EventSummarizer.EventSummary>emptyList());
    }

    /**
     * Turns the evaluations counted so far into their summary events, leaving the counters empty.
     * <p>
     * Counters live only in memory, so whatever has not been summarized is what a crash takes with
     * it. Summarizing at points where the events are about to be made durable is what bounds that
     * loss, and it costs nothing in accuracy: LaunchDarkly sums the counters of every summary it
     * receives, so several summaries covering the same evaluations count the same as one.
     * <p>
     * A summary that cannot be serialized is dropped and logged rather than counted back in. Losing
     * an aggregate of evaluations the SDK promised to report is bad, but everything the encoder can
     * fail on is a property of the data it was handed -- the output stream is a byte array and cannot
     * fail transiently -- so putting the counters back would make every later flush fail on the same
     * summary while the counters behind it grew without bound.
     * <p>
     * The counters are taken under the lock and encoded outside it, as in {@link #drain}, so a thread
     * recording an evaluation waits only for a reference swap and never for the encoder.
     *
     * @return one JSON object per summary, empty if there was nothing counted or nothing serialized
     */
    List<byte[]> serializeSummariesAndReset() {
        List<EventSummarizer.EventSummary> summaries;
        synchronized (this) {
            if (summarizer.isEmpty()) {
                return Collections.emptyList();
            }
            summaries = summarizer.getSummariesAndReset();
        }
        List<byte[]> serialized = new ArrayList<>(summaries.size());
        for (EventSummarizer.EventSummary summary : summaries) {
            byte[] bytes = writeSingleObject(new Event[0], Collections.singletonList(summary));
            if (bytes != null) {
                serialized.add(bytes);
            }
        }
        return serialized;
    }

    /**
     * Serializes a run of events with one encoder instead of one per event.
     * <p>
     * Each event still becomes its own JSON object, because the store frames them individually and a
     * frame holding several objects could not be spliced into a payload. What the run shares is the
     * output stream, the writer and the UTF-8 lookup, which {@link #writeSingleObject} otherwise
     * allocates on every call.
     * <p>
     * Deliberately not synchronized, for the reason {@link #serialize} is not.
     *
     * @param pending the events, in the order they were recorded
     * @return one JSON object per event that survived sampling and serialized
     */
    List<byte[]> serializeAll(List<Event> pending) {
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> serialized = new ArrayList<>(pending.size());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), INITIAL_OUTPUT_BUFFER_SIZE);
        Event[] one = new Event[1];
        for (Event event : pending) {
            if (!Sampler.shouldSample(event.getSamplingRatio())) {
                continue;
            }
            one[0] = event;
            byte[] bytes = writeSingleObject(one, Collections.<EventSummarizer.EventSummary>emptyList(),
                    outputStream, writer);
            if (bytes != null) {
                serialized.add(bytes);
            }
        }
        return serialized;
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
    private byte[] writeSingleObject(Event[] events, List<EventSummarizer.EventSummary> summaries) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), INITIAL_OUTPUT_BUFFER_SIZE);
        return writeSingleObject(events, summaries, outputStream, writer);
    }

    /**
     * @param outputStream reset before the write, so it may be shared across a run
     * @param writer must wrap {@code outputStream}, and holds nothing buffered on entry
     */
    private byte[] writeSingleObject(Event[] events, List<EventSummarizer.EventSummary> summaries,
                                     ByteArrayOutputStream outputStream, Writer writer) {
        outputStream.reset();
        int written;
        try {
            written = formatter.writeOutputEvents(events, summaries, writer);
            writer.flush();
        } catch (Exception e) {
            logger.error("Dropping unserializable {}: {}", describe(events),
                    LogValues.exceptionSummary(e));
            logger.debug("{}", LogValues.exceptionTrace(e));
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
            logger.error("Dropping unserializable {}: the encoder did not produce a single JSON"
                    + " object", describe(events));
            return null;
        }
        return Arrays.copyOfRange(all, 1, all.length - 1);
    }

    /** Names what is being dropped, since this encoder is handed one event or one summary. */
    private static String describe(Event[] events) {
        return events.length == 0 ? "summary event"
                : "event of type " + events[0].getClass().getSimpleName();
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
