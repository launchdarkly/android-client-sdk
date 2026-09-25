package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventOutputFormatter;
import com.launchdarkly.sdk.internal.events.EventSummarizer;
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
        this(allAttributesPrivate, privateAttributes, perContextSummarization, maxContexts, logger, true);
    }

    /**
     * @param cacheContexts false to encode a context afresh for every event instead of reusing the
     *   encoding across a run that shares one. Only a benchmark measuring what the reuse is worth has
     *   any reason to pass false.
     */
    OutboundEventBuffer(
            boolean allAttributesPrivate,
            Collection<AttributeRef> privateAttributes,
            boolean perContextSummarization,
            int maxContexts,
            LDLogger logger,
            boolean cacheContexts
    ) {
        // Only the private-attribute settings affect the output; the rest of EventsConfiguration
        // describes the delivery behavior that the processor now handles itself, capacity included.
        EventsConfiguration outputConfig = new EventsConfiguration(allAttributesPrivate, 0,
                null, 0, null, null, 1, null, 0, false, false, privateAttributes,
                perContextSummarization);
        this.formatter = new EventOutputFormatter(outputConfig);
        this.fullEventWriter = new FullEventWriter(allAttributesPrivate, privateAttributes, cacheContexts);
        this.summarizer = new SummaryEventAccumulator(perContextSummarization);
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
        summarizer.summarize(event);
        return true;
    }

    /**
     * Serializes one event into the bytes it will be sent as, for a caller that keeps events
     * somewhere other than this buffer's list.
     * <p>
     * Serializing at record time rather than at flush is what lets an event be written somewhere that
     * outlives the process: bytes can be appended to a log, an {@link Event} cannot.
     * <p>
     * Deliberately not synchronized. It writes only locals and the context cache, which guards itself,
     * so it shares nothing with a thread recording an event, and taking the lock would make an encode
     * wait on a counter increment and the other way round.
     *
     * @param event the event
     * @return the event's JSON object, or null if it was dropped by sampling or could not be
     *   serialized
     */
    byte[] serialize(Event event) {
        if (!Sampler.shouldSample(event.getSamplingRatio())) {
            return null;
        }
        return writeFullEvent(event, new JsonByteWriter(INITIAL_OUTPUT_BUFFER_SIZE));
    }

    /**
     * Takes the counters and forgets which contexts they were counted for.
     * <p>
     * The two have to move together. Every path that resets the summarizer has to come through here,
     * because one that reset the counters alone would spend the cardinality limit on contexts whose
     * counters had already gone out, and the limit would never lift.
     * <p>
     * Separate from {@link #encode} so that the caller can take the counters in the same critical
     * section it lifts the full events in. An evaluation writes a counter and a full event, and a
     * flush that took the two at different moments could split one evaluation across two payloads.
     */
    synchronized List<SummaryEventAccumulator.Summary> takeSummaries() {
        List<SummaryEventAccumulator.Summary> summaries = summarizer.getSummariesAndReset();
        if (countedContexts != null) {
            countedContexts.clear();
        }
        return summaries;
    }

    /**
     * Takes the counters and serializes them in one call, for a caller with nothing to coordinate the
     * take with.
     * <p>
     * Not for the commit path. That has to take the counters in the same critical section it takes the
     * pending events in, or a commit can stage an evaluation's counter and leave its full event behind.
     */
    List<byte[]> serializeSummariesAndReset() {
        return serializeSummaries(takeSummaries());
    }

    /**
     * Turns summary counters already taken from {@link #takeSummaries} into their summary events.
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
     * Deliberately not synchronized, for the reason {@link #serialize} is not: the counters are taken
     * under the caller's lock and encoded outside it, so a thread recording an evaluation waits only
     * for a reference swap and never for the encoder.
     *
     * @param summaries the counters taken for this commit
     * @return one JSON object per summary, empty if there was nothing counted or nothing serialized
     */
    List<byte[]> serializeSummaries(List<SummaryEventAccumulator.Summary> summaries) {
        if (summaries.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> serialized = new ArrayList<>(summaries.size());
        JsonByteWriter writer = new JsonByteWriter(INITIAL_OUTPUT_BUFFER_SIZE);
        for (SummaryEventAccumulator.Summary summary : summaries) {
            // The aggregated accumulator hands back its one summary whether or not anything was
            // counted, and an empty one is not an event.
            if (summary.isEmpty()) {
                continue;
            }
            byte[] bytes = writeSummary(summary, writer);
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
     * writer's buffer, which {@link #serialize} otherwise allocates on every call -- and, because a run
     * usually shares one context, the encoding of that context too.
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
        JsonByteWriter writer = new JsonByteWriter(INITIAL_OUTPUT_BUFFER_SIZE);
        for (Event event : pending) {
            if (!Sampler.shouldSample(event.getSamplingRatio())) {
                continue;
            }
            byte[] bytes = writeFullEvent(event, writer);
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
     * @param writer reset before the write, so it may be shared across a run
     * @return the object's bytes, or null if the event could not be serialized
     */
    private byte[] writeFullEvent(Event event, JsonByteWriter writer) {
        writer.reset();
        try {
            if (!fullEventWriter.write(event, writer)) {
                return writeSingleObject(event);
            }
        } catch (Exception e) {
            logDropped("event of type " + event.getClass().getSimpleName(), e);
            return null;
        }
        return writer.toByteArray();
    }

    private byte[] writeSummary(SummaryEventAccumulator.Summary summary, JsonByteWriter writer) {
        writer.reset();
        try {
            fullEventWriter.writeSummary(summary, writer);
        } catch (Exception e) {
            logDropped("summary event", e);
            return null;
        }
        return writer.toByteArray();
    }

    /**
     * Serializes exactly one output event with {@link EventOutputFormatter} and returns the JSON object
     * on its own.
     * <p>
     * The formatter only offers to write a whole request body, which is a JSON array, and the
     * single-event method beside it is private. So the array is written and its brackets are dropped,
     * which is sound precisely because the count it returns says how many objects are in there: one
     * object means the bytes between the brackets are that object.
     *
     * @return the object's bytes, or null if the formatter did not write exactly one event
     */
    private byte[] writeSingleObject(Event event) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        Writer writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), INITIAL_OUTPUT_BUFFER_SIZE);
        int written;
        try {
            written = formatter.writeOutputEvents(new Event[]{ event },
                    Collections.<EventSummarizer.EventSummary>emptyList(), writer);
            writer.flush();
        } catch (Exception e) {
            logDropped("event of type " + event.getClass().getSimpleName(), e);
            return null;
        }
        if (written != 1) {
            return null;
        }
        byte[] all = outputStream.toByteArray();
        if (all.length < 3 || all[0] != '[' || all[all.length - 1] != ']') {
            // Not the shape this depends on. Refusing the event is the safe reading: a frame that is
            // not one JSON object would corrupt every payload it was later spliced into.
            logger.error("Dropping unserializable event of type {}: the encoder did not produce a"
                    + " single JSON object", event.getClass().getSimpleName());
            return null;
        }
        return Arrays.copyOfRange(all, 1, all.length - 1);
    }

    private void logDropped(String what, Exception e) {
        logger.error("Dropping unserializable {}: {}", what, LogValues.exceptionSummary(e));
        logger.debug("{}", LogValues.exceptionTrace(e));
    }

    /**
     * Serializes a run of events, together with the evaluations counted beside it, into the payload
     * they will be sent as.
     * <p>
     * Deliberately takes no lock. The caller has already taken both the run and the counters, so
     * there is nothing left here to protect, and the encode is the dominant cost on this path --
     * holding a lock across it would make a thread recording an event wait for it. Recording happens
     * on whichever thread evaluated a flag, which on Android is usually the main one.
     * <p>
     * Each event and each summary is written as its own object, so one that cannot be serialized is
     * dropped and logged and the rest are sent. Everything the encoder can fail on is a property of the
     * data it was handed -- the output stream is a byte array and cannot fail transiently -- so putting
     * a failed item back would only make every later flush fail too.
     *
     * @param run the full events to send, in the order they were recorded
     * @param summaries the counters taken alongside that run
     * @return the payload to send, or null if there was nothing to send
     * @throws IOException if the payload could not be assembled
     */
    Payload encode(List<Event> run, List<SummaryEventAccumulator.Summary> summaries) throws IOException {
        if (run.isEmpty() && summaries.isEmpty()) {
            return null;
        }
        JsonByteWriter writer = new JsonByteWriter(INITIAL_OUTPUT_BUFFER_SIZE);
        ByteArrayOutputStream payload = new ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_SIZE);
        payload.write('[');
        int outputEventCount = 0;
        for (Event event : run) {
            byte[] serialized = writeFullEvent(event, writer);
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
            if (summary.isEmpty()) {
                continue;
            }
            byte[] serialized = writeSummary(summary, writer);
            if (serialized == null) {
                continue;
            }
            if (outputEventCount != 0) {
                payload.write(',');
            }
            payload.write(serialized);
            outputEventCount++;
        }
        if (outputEventCount == 0) {
            return null;
        }
        payload.write(']');
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
