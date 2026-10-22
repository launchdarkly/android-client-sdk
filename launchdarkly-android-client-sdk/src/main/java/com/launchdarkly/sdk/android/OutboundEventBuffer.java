package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.AggregatedEventSummarizer;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventOutputFormatter;
import com.launchdarkly.sdk.internal.events.EventSummarizer;
import com.launchdarkly.sdk.internal.events.EventSummarizerInterface;
import com.launchdarkly.sdk.internal.events.EventsConfiguration;
import com.launchdarkly.sdk.internal.events.SameContextPerContextEventSummarizer;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
     * The context counted last, compared by reference so that the set above is only hashed into when the
     * context changes. {@link LDContext#hashCode()} is not cached, and an application evaluates against
     * one instance until it identifies again.
     */
    private LDContext lastCountedContext;

    /**
     * Set when an event or a summary was dropped for failing to serialize, until the processor asks.
     * Serialization runs on whichever thread commits, so this is the one piece of state here that is
     * written outside the caller's lock.
     */
    private final AtomicBoolean serializationFailed = new AtomicBoolean(false);

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
                ? new SameContextPerContextEventSummarizer()
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
     * <p>
     * Not synchronized: the caller holds its own lock across this and the rest of the recording, as it
     * does across {@link #takeSummaries}, and a second monitor inside it would only be taken for nothing.
     *
     * @param event the evaluation
     * @return false if this event was not counted, because counting it would have meant holding
     *   a context beyond the configured capacity
     */
    boolean summarize(Event.FeatureRequest event) {
        // Checked here rather than in DirectEventProcessor, for the same reason the sampling ratio
        // is: this is where an event arrives from outside. The processor summarizes from the values
        // themselves, which have no such flag. java-sdk-internal's DefaultEventProcessor, which this
        // path replaced, honored it, and a counter is the one thing no later stage can reconstruct.
        if (event.isExcludeFromSummaries()) {
            return true;
        }
        return summarize(event.getCreationDate(), event.getKey(), event.getVersion(), event.getVariation(),
                event.getValue(), event.getDefaultVal(), event.getContext());
    }

    /**
     * As {@link #summarize(Event.FeatureRequest)}, from the evaluation's values, so that an evaluation
     * nobody needs a full event for costs no event to be built.
     */
    boolean summarize(long timestamp, String flagKey, int flagVersion, int variation, LDValue value,
                      LDValue defaultValue, LDContext context) {
        // Only a context that is not being counted yet can be turned away, so reaching the limit costs
        // an application evaluating against one context nothing, however many evaluations it does.
        if (countedContexts != null && context != lastCountedContext) {
            if (!countedContexts.contains(context)) {
                if (countedContexts.size() >= maxContexts) {
                    return false;
                }
                countedContexts.add(context);
            }
            lastCountedContext = context;
        }
        summarizer.summarizeEvent(timestamp, flagKey, flagVersion, variation, value, defaultValue, context);
        return true;
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
     * Sampling is not applied here. {@link DirectEventProcessor} draws for it once, as the event is
     * recorded, and drawing again for the same event would square the odds against it.
     *
     * @param event the event
     * @return the event's JSON object, or null if it could not be serialized
     */
    byte[] serialize(Event event) {
        return writeSingleObject(new Event[]{ event }, Collections.<EventSummarizer.EventSummary>emptyList());
    }

    /**
     * Takes the counters and forgets which contexts they were counted for.
     * <p>
     * The two have to move together. Every path that resets the summarizer has to come through here,
     * because one that reset the counters alone would spend the cardinality limit on contexts whose
     * counters had already gone out, and the limit would never lift.
     * <p>
     * Separate from {@link #serializeSummaries} so that the caller can take the counters in the same
     * critical section it lifts the full events in. An evaluation writes a counter and a full event,
     * and a commit that took the two at different moments could split one evaluation across two
     * payloads.
     * Not synchronized, for the reason {@link #summarize(Event.FeatureRequest)} is not.
     */
    List<EventSummarizer.EventSummary> takeSummaries() {
        List<EventSummarizer.EventSummary> summaries = summarizer.getSummariesAndReset();
        if (countedContexts != null) {
            countedContexts.clear();
            lastCountedContext = null;
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
     * A summary that cannot be serialized is dropped whole and logged rather than counted back in, and
     * {@link #takeSerializationFailure} reports it, so the flush that committed it answers false. Losing
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
    List<byte[]> serializeSummaries(List<EventSummarizer.EventSummary> summaries) {
        if (summaries.isEmpty()) {
            return Collections.emptyList();
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
     * Deliberately not synchronized, and not sampling, for the reasons {@link #serialize} is neither.
     *
     * @param pending the events, in the order they were recorded
     * @return one JSON object per event that serialized
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
            serializationFailed.set(true);
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
            serializationFailed.set(true);
            logger.error("Dropping unserializable {}: the encoder did not produce a single JSON"
                    + " object", describe(events));
            return null;
        }
        return Arrays.copyOfRange(all, 1, all.length - 1);
    }

    /**
     * @return whether anything was dropped for failing to serialize since this was last called
     */
    boolean takeSerializationFailure() {
        return serializationFailed.getAndSet(false);
    }

    /** Names what is being dropped, since this encoder is handed one event or one summary. */
    private static String describe(Event[] events) {
        return events.length == 0 ? "summary event"
                : "event of type " + events[0].getClass().getSimpleName();
    }}
