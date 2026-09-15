package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.Event;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * The Android counterpart of the iOS {@code EventPersistenceBenchmark}: what it costs to record an
 * analytics event, so the encoding research can be argued from measurements on this platform rather
 * than from the ones taken on iOS.
 * <p>
 * This exists because the two platforms are not obviously comparable. iOS encodes with {@code Codable},
 * Android encodes with {@code EventOutputFormatter} in java-sdk-internal, and a JIT with escape
 * analysis behaves nothing like ahead-of-time compiled Swift. The iOS finding -- that the cost is in
 * the serializer's per-value machinery rather than in walking and redacting the context -- is a claim
 * about {@code Codable}, and nothing about it transfers here by assumption.
 * <p>
 * Skipped unless {@code LD_EVENT_BENCH=1}, because nothing here asserts anything a regression would
 * trip and the timing loops take far longer than a unit test should:
 * <pre>
 *   LD_EVENT_BENCH=1 ./gradlew :launchdarkly-android-client-sdk:testDebugUnitTest \
 *       --tests '*EventPersistenceBenchmark*'
 * </pre>
 * <p>
 * <b>Read the numbers as ratios, not absolutes.</b> Three reasons. This runs on a desktop JVM rather
 * than on ART on a device, which is the same caveat the iOS figures carry about running on a Mac. It
 * is a hand-rolled timing loop rather than JMH, so it defends against the two failure modes that
 * matter -- an unwarmed JIT and dead-code elimination -- and against nothing else. And the JVM's
 * escape analysis can delete allocations here that a real caller would pay for, which flatters any
 * row whose result is discarded.
 */
public class EventPersistenceBenchmark {
    private static final String FLAG_KEY = "benchmark-flag";
    private static final int FLAG_VERSION = 7;
    private static final int VARIATION = 1;

    /**
     * Fixed rather than {@code System.currentTimeMillis()}, so that repeated runs encode identical
     * bytes and the clock call is not charged to the encoder. The event-construction row below covers
     * what the real path pays around it.
     */
    private static final long CREATION_DATE = 1_700_000_000_000L;

    /**
     * One already serialized event, byte for byte what the iOS benchmark stages, so the store figures on the
     * two platforms are measuring the same amount of work reaching the same kind of file.
     */
    private static final String SERIALIZED_EVENT =
            "{\"kind\":\"feature\",\"key\":\"benchmark-flag\",\"value\":true,\"default\":false,"
                    + "\"variation\":1,\"version\":7,\"creationDate\":1740000000000}";

    /** How many times each timed loop is repeated; the fastest round is reported. */
    private static final int ROUNDS = 5;

    /**
     * The bulk sizes the recording figures are swept over.
     * <p>
     * Chosen around the store's 16 KiB staging threshold rather than for being round. At the event size used
     * here a hundred events stay under it and cause no write at all, a thousand cross it a handful of times,
     * and ten thousand cross it often enough that the deferred column is an average rather than a report on
     * one commit. The same three are used by the iOS benchmark, so the two platforms' tables line up row for
     * row.
     */
    private static final int[] BULK_SIZES = {100, 1_000, 10_000};

    /**
     * How many calls it takes to get this loop compiled, independent of how many the measurement then makes.
     * <p>
     * The smallest bulk size is below it on purpose, which is exactly why this is not a share of the round.
     */
    private static final int JIT_WARMUP = 10_000;

    /**
     * Consumes results so that the JIT cannot prove the work is unused and delete it. Never read for
     * its value.
     */
    private static long blackhole;

    /**
     * Holds an allocation so that escape analysis cannot scalar-replace it. Assigning to a static field
     * is what makes the object genuinely exist, which is the difference between measuring an allocation
     * and measuring nothing at all.
     */
    private static Object escaped;

    @Rule
    public TemporaryFolder eventsDirectory = new TemporaryFolder();

    private final LDLogger logger = LDLogger.withAdapter(Logs.none(), "");

    @Before
    public void requireBenchmarking() {
        assumeTrue("Set LD_EVENT_BENCH=1 to measure event recording",
                "1".equals(System.getenv("LD_EVENT_BENCH")));
    }

    /**
     * M1 and M2 for Android: where the time goes when one feature event is serialized, across the
     * context shapes and privacy settings that change how much work redaction has to do.
     * <p>
     * The shape of the result is the interesting part. If the cost is dominated by walking and
     * redacting the context, it should grow with the number of attributes and grow again when private
     * attributes force the filtering path. If it is dominated by the serializer's fixed per-event
     * machinery, it should be far flatter than the attribute counts suggest.
     */
    @Test
    public void encodingCostByContextShape() {
        List<Measurement> building = new ArrayList<>();
        List<Measurement> serializing = new ArrayList<>();

        for (ContextShape shape : ContextShape.values()) {
            final LDContext context = makeContext(shape, "benchmark-key");

            building.add(new Measurement(shape.label, measure(200_000, i -> {
                escaped = featureEvent(context, true);
            })));

            for (PrivacyShape privacy : privacyShapes()) {
                final OutboundEventBuffer buffer = makeBuffer(privacy);

                byte[] sample = buffer.serialize(featureEvent(context, true));
                assertNotNull("the corpus produced an event that will not serialize", sample);

                double nanos = measure(50_000, i -> {
                    byte[] out = buffer.serialize(featureEvent(context, true));
                    blackhole += out == null ? 0 : out.length;
                });
                serializing.add(new Measurement(
                        shape.label + ", " + privacy.name + ", " + sample.length + " bytes", nanos));
            }
        }

        report("building one feature event, no serialization", building);
        report("serializing one feature event", serializing);
    }

    /**
     * The figures a customer actually pays, mirroring what {@link DirectEventProcessor} does per call.
     * <p>
     * The three rows are the three paths through {@code recordEvaluationEvent} and {@code recordCustomEvent}:
     * an evaluation of an untracked flag is a counter increment; an evaluation of a tracked flag also
     * serializes and stages a full event; and a {@code track} is a commit point, which additionally turns
     * the counters into summary events and writes everything to the log.
     * <p>
     * The sequence is reproduced here rather than driven through the processor itself, which would need
     * an HTTP server, a scheduler and a {@code ClientContext} whose costs would swamp what is being
     * measured. It is kept deliberately close to the original; if the processor changes, this should too.
     */
    @Test
    public void recordingCostPerCall() throws IOException {
        final OutboundEventBuffer buffer = makeBuffer(noRedaction());
        final EventStore store = EventStore.create(eventsDirectory.getRoot(), "benchmark-key",
                "benchmark", Integer.MAX_VALUE, logger);
        final LDContext context = makeContext(ContextShape.STUB, "benchmark-key");

        try {
            List<Measurement> results = new ArrayList<>();

            results.add(new Measurement("evaluation, summary only", measure(200_000, i -> {
                buffer.summarize(featureEvent(context, false));
            })));

            results.add(new Measurement("evaluation, full event", measure(50_000, i -> {
                Event.FeatureRequest event = featureEvent(context, true);
                buffer.summarize(event);
                byte[] out = buffer.serialize(event);
                if (out != null) {
                    store.stage(out);
                    blackhole += out.length;
                }
            })));

            results.add(new Measurement("evaluation + track (commit point)", measure(20_000, i -> {
                buffer.summarize(featureEvent(context, false));
                byte[] out = buffer.serialize(
                        new Event.Custom(CREATION_DATE, "benchmark-event", context, LDValue.ofNull(), null));
                if (out != null) {
                    store.stage(out);
                    blackhole += out.length;
                }
                for (byte[] summary : buffer.serializeSummariesAndReset()) {
                    store.stage(summary, true);
                    blackhole += summary.length;
                }
                store.commit();
            })));

            report("recording, per call", results);
        } finally {
            store.close();
        }
    }

    /**
     * What a bulk of recordings costs, split by which thread pays for it.
     * <p>
     * Every other figure here prices one call, which hides the thing that decides whether the store is
     * affordable: it does not write on the thread that staged an event. Bytes accumulate to 16 KiB and then
     * the write is handed to {@code commitExecutor}, so a row that times {@code stage} alone reports an array
     * copy and silently omits the write it caused.
     * <p>
     * So this records a bulk the way an application would, and prices both sides of the hand-off:
     * <ul>
     *   <li><i>caller thread</i> is wall time on the thread that recorded. It includes whatever the commit
     *       executor made it wait for on {@code bufferLock}, because a real evaluation waits for that too.</li>
     *   <li><i>commit thread</i> is how long the executor spent running the commits those recordings caused.
     *       Measured by wrapping the executor rather than inferred, which is the part the staging rows omit.</li>
     * </ul>
     * <p>
     * Deliberately no {@code closeBatch} and no {@code remove}. Those run on the delivery path once per flush
     * interval, so folding them into a per-event figure answers a question nobody asked.
     */
    @Test
    public void callerThreadCostOfABulk() {
        final byte[] serialized = SERIALIZED_EVENT.getBytes(StandardCharsets.UTF_8);

        final TimingExecutor commits = new TimingExecutor();
        final EventStore[] store = new EventStore[1];
        final List<EventStore> toClose = new ArrayList<>();

        final Runnable newRound = () -> {
            if (store[0] != null) {
                toClose.add(store[0]);
            }
            store[0] = new EventStore(new File(eventsDirectory.getRoot(), UUID.randomUUID().toString()),
                    "benchmark", Integer.MAX_VALUE, logger, commits);
            // Opens the output stream outside the timed region, so the round's first event does not pay for it.
            store[0].stage(serialized);
            store[0].commit();
        };

        final OutboundEventBuffer buffer = makeBuffer(noRedaction());
        final LDContext context = makeContext(ContextShape.STUB, "benchmark-key");

        try {
            // Three sizes, because the 16 KiB threshold is what decides whether there is any deferred work at
            // all. A hundred events of this size never reach it: nothing is written, and the whole cost is the
            // caller's. A thousand cross it about eight times and ten thousand about eighty.
            for (int bulk : BULK_SIZES) {
                List<BulkMeasurement> results = new ArrayList<>();

                results.add(new BulkMeasurement("staging a serialized event",
                        measureBulk(bulk, commits, newRound, i -> store[0].stage(serialized))));

                // The same bulk through the whole recording path, which is what puts the write in proportion.
                // Serializing is by far the larger cost on this platform, and a figure for the store alone
                // invites being read as though it were not.
                results.add(new BulkMeasurement("evaluation of a tracked flag, end to end",
                        measureBulk(bulk, commits, newRound, i -> {
                            Event.FeatureRequest event = featureEvent(context, true);
                            buffer.summarize(event);
                            byte[] out = buffer.serialize(event);
                            if (out != null) {
                                store[0].stage(out);
                                blackhole += out.length;
                            }
                        })));

                reportBulk("recording " + count(bulk) + " events, nothing delivered", results);
            }
        } finally {
            for (EventStore closing : toClose) {
                closing.close();
            }
            if (store[0] != null) {
                store[0].close();
            }
            commits.shutdown();
        }
    }

    /**
     * What a commit point costs when it goes badly, rather than on average.
     * <p>
     * {@code track} and {@code identify} commit on the caller's thread by design -- that is the durable
     * barrier -- so this is the one figure here an application can observe as a stall. The mean is the number
     * that gets quoted; the tail is the number a user notices.
     * <p>
     * The counterpart iOS test runs this against a SQLite store as well, where the tail is the point: a
     * database in WAL mode folds its write-ahead log back into the file every thousand pages, on whichever
     * thread happens to be committing, so one unlucky caller pays for all of them. An append has no such
     * thing, and these rows are what that claim is measured against.
     * <p>
     * Two rows per size, for the two things a caller's thread actually does. {@code track} commits where it
     * stands, so its tail is the write. An evaluation only stages and lets the executor write, so its tail is
     * whatever the executor made it wait for -- which is the cost the deferred column cannot show.
     * <p>
     * <b>p99.9 only separates from the worst at ten thousand samples.</b> Below that the index falls on the
     * last sample and the two columns are the same number by construction. They are left side by side so the
     * table lines up across the sweep, and so the size at which they part is visible rather than asserted.
     */
    @Test
    public void commitPointOutliers() throws IOException {
        final byte[] serialized = SERIALIZED_EVENT.getBytes(StandardCharsets.UTF_8);
        final OutboundEventBuffer buffer = makeBuffer(noRedaction());
        final LDContext context = makeContext(ContextShape.STUB, "benchmark-key");

        // A store of its own for each size and each row, so that a log inherited from an earlier measurement
        // does not charge the next one for the file it had already grown.
        for (int calls : BULK_SIZES) {
            final EventStore committing = EventStore.create(eventsDirectory.newFolder(), "benchmark-key",
                    "benchmark", Integer.MAX_VALUE, logger);
            final EventStore staging = EventStore.create(eventsDirectory.newFolder(), "benchmark-key",
                    "benchmark", Integer.MAX_VALUE, logger);
            try {
                List<DistributionMeasurement> results = new ArrayList<>();

                results.add(new DistributionMeasurement("track, commits on this thread",
                        measureDistribution(calls, i -> {
                            committing.stage(serialized);
                            committing.commit();
                        })));

                results.add(new DistributionMeasurement("evaluation, write handed off",
                        measureDistribution(calls, i -> {
                            Event.FeatureRequest event = featureEvent(context, true);
                            buffer.summarize(event);
                            byte[] out = buffer.serialize(event);
                            if (out != null) {
                                staging.stage(out);
                                blackhole += out.length;
                            }
                        })));

                reportDistribution("one call, " + count(calls) + " of them, on the caller's thread", results);
            } finally {
                committing.close();
                staging.close();
            }
        }
    }

    /**
     * Whether O2 -- caching the encoded context and reusing it while the context has not changed --
     * could work here, which comes down to what the cache key would cost to check.
     * <p>
     * Android differs from iOS in a way that matters. {@code LDContext} is an immutable object passed by
     * reference, and nothing on the event path copies it, so an application that holds one context hands
     * the same instance to every event and {@code equals} can settle on reference identity. iOS had to
     * reach that fast path by not rebuilding a value type. The row that decides the question is the other
     * one: an application that rebuilds its context between evaluations produces an equal object sharing
     * nothing, and that comparison is the price of a cache hit in the worst case.
     */
    @Test
    public void contextComparisonCost() {
        List<Measurement> comparisons = new ArrayList<>();
        List<Measurement> hashing = new ArrayList<>();

        for (ContextShape shape : ContextShape.values()) {
            final LDContext context = makeContext(shape, "benchmark-key");
            final LDContext sameInstance = context;
            final LDContext rebuilt = makeContext(shape, "benchmark-key");

            // Both must be equal, or the figures below measure an early exit rather than a comparison.
            assertEquals(shape.label, context, sameInstance);
            assertEquals(shape.label, context, rebuilt);

            comparisons.add(new Measurement("same instance, " + shape.label, measure(500_000, i -> {
                blackhole += context.equals(sameInstance) ? 1 : 0;
            })));
            comparisons.add(new Measurement("independently built, " + shape.label, measure(200_000, i -> {
                blackhole += context.equals(rebuilt) ? 1 : 0;
            })));
            hashing.add(new Measurement(shape.label, measure(500_000, i -> {
                blackhole += context.hashCode();
            })));
        }

        report("comparing two equal contexts", comparisons);
        report("hashing a context", hashing);
    }

    /**
     * What the deferred half of the bill costs: summaries are not serialized per evaluation but at
     * commit points and deliveries, and the size of that payment depends on how many distinct flags the
     * counters have accumulated.
     * <p>
     * Counting and serializing are reported separately, because only the second is deferred -- the
     * counter increment is already paid on every evaluation.
     */
    @Test
    public void summarySerializationCost() {
        final LDContext context = makeContext(ContextShape.STUB, "benchmark-key");
        List<Measurement> results = new ArrayList<>();

        for (final int flagCount : new int[] {1, 10, 50}) {
            final OutboundEventBuffer buffer = makeBuffer(noRedaction());

            double countingOnly = measure(20_000, i -> {
                for (int flag = 0; flag < flagCount; flag++) {
                    buffer.summarize(summaryEvent(context, flag));
                }
            });
            // Drained, so the counters left behind above do not inflate the first serialized round.
            buffer.serializeSummariesAndReset();

            double countingAndSerializing = measure(20_000, i -> {
                for (int flag = 0; flag < flagCount; flag++) {
                    buffer.summarize(summaryEvent(context, flag));
                }
                for (byte[] summary : buffer.serializeSummariesAndReset()) {
                    blackhole += summary.length;
                }
            });

            results.add(new Measurement(flagCount + " flags: counting only", countingOnly));
            results.add(new Measurement(flagCount + " flags: counting + serializing",
                    countingAndSerializing));
            results.add(new Measurement(flagCount + " flags: serializing alone",
                    countingAndSerializing - countingOnly));
        }

        report("turning counters into a summary event", results);
    }

    // MARK: Corpus

    /**
     * The same five shapes the iOS benchmark uses, so the two sets of numbers describe the same
     * contexts.
     */
    private enum ContextShape {
        KEY_ONLY("key only"),
        STUB("stub, 9 attributes"),
        WIDE("20 flat attributes"),
        NESTED("nested attributes"),
        MULTI("multi-context");

        final String label;

        ContextShape(String label) {
            this.label = label;
        }
    }

    private static LDContext makeContext(ContextShape shape, String key) {
        switch (shape) {
            case KEY_ONLY:
                return LDContext.create(key);
            case STUB:
                return stubContext(key);
            case WIDE: {
                com.launchdarkly.sdk.ContextBuilder builder = LDContext.builder(key).name("Wide");
                for (int i = 0; i < 20; i++) {
                    builder.set("attribute" + i, "value" + i);
                }
                return builder.build();
            }
            case NESTED:
                return LDContext.builder(key)
                        .name("Nested")
                        .set("address", LDValue.buildObject()
                                .put("street", "1 Main St")
                                .put("city", "Springfield")
                                .put("geo", LDValue.buildObject().put("lat", 1.5).put("lon", -2.5).build())
                                .build())
                        .set("tags", LDValue.buildArray()
                                .add("a").add("b").add("c").add("d").add("e").build())
                        .build();
            case MULTI:
                return LDContext.createMulti(
                        stubContext(key),
                        LDContext.builder(ContextKind.of("device"), "device-" + key)
                                .set("os", LDValue.buildObject()
                                        .put("name", "Android").put("version", 14).build())
                                .build());
            default:
                throw new IllegalArgumentException(shape.name());
        }
    }

    /** Nine attributes, matching the shape iOS calls its stub context. */
    private static LDContext stubContext(String key) {
        return LDContext.builder(key)
                .name("Stub Context")
                .set("firstName", "Ada")
                .set("lastName", "Lovelace")
                .set("email", "ada@example.com")
                .set("country", "US")
                .set("age", 36)
                .set("score", 12.5)
                .set("verified", true)
                .set("plan", "enterprise")
                .build();
    }

    /** The privacy settings that change what redaction has to do. */
    private static final class PrivacyShape {
        final String name;
        final boolean allAttributesPrivate;
        final Collection<AttributeRef> privateAttributes;

        PrivacyShape(String name, boolean allAttributesPrivate, Collection<AttributeRef> privateAttributes) {
            this.name = name;
            this.allAttributesPrivate = allAttributesPrivate;
            this.privateAttributes = privateAttributes;
        }
    }

    private static PrivacyShape noRedaction() {
        return new PrivacyShape("no redaction", false, Collections.<AttributeRef>emptyList());
    }

    private static List<PrivacyShape> privacyShapes() {
        return Arrays.asList(
                noRedaction(),
                new PrivacyShape("1 global private", false,
                        Collections.singletonList(AttributeRef.fromLiteral("email"))),
                new PrivacyShape("all private", true, Collections.<AttributeRef>emptyList()));
    }

    private OutboundEventBuffer makeBuffer(PrivacyShape privacy) {
        return new OutboundEventBuffer(Integer.MAX_VALUE, privacy.allAttributesPrivate,
                privacy.privateAttributes, true, logger);
    }

    private static Event.FeatureRequest featureEvent(LDContext context, boolean requireFullEvent) {
        return new Event.FeatureRequest(CREATION_DATE, FLAG_KEY, context, FLAG_VERSION, VARIATION,
                LDValue.of(true), LDValue.of(false), null, null, requireFullEvent, null, false);
    }

    private static Event.FeatureRequest summaryEvent(LDContext context, int flagIndex) {
        return new Event.FeatureRequest(CREATION_DATE, FLAG_KEY + "-" + flagIndex, context,
                FLAG_VERSION, VARIATION, LDValue.of(true), LDValue.of(false), null, null, false, null,
                false);
    }

    // MARK: Harness

    /**
     * Times {@code body}, returning nanoseconds per iteration.
     * <p>
     * Warmed first, because a JVM measured cold reports the interpreter rather than the compiled code.
     * Then run several times with the fastest round reported: a garbage collection or a descheduled
     * thread can only ever make a round slower, so the minimum is the closest estimate of the work
     * itself. That is also why these numbers should not be read as what a busy device would see.
     */
    private double measure(int iterations, IntConsumer body) {
        int warmup = Math.max(10_000, iterations / 4);
        for (int i = 0; i < warmup; i++) {
            body.accept(i);
        }

        double best = Double.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                body.accept(i);
            }
            long elapsed = System.nanoTime() - start;
            best = Math.min(best, (double) elapsed / iterations);
        }
        return best;
    }

    /**
     * An {@link Executor} that reports how long it spent running what it was handed.
     * <p>
     * This is what makes the deferred column a measurement rather than an inference. The store hands its
     * commits here, and each one is timed on the thread that runs it, so the figure is the write itself
     * rather than the difference between two numbers taken elsewhere.
     */
    private static final class TimingExecutor implements Executor {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "benchmark-commit");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicLong busyNanos = new AtomicLong();

        @Override
        public void execute(Runnable command) {
            delegate.execute(() -> {
                long start = System.nanoTime();
                try {
                    command.run();
                } finally {
                    busyNanos.addAndGet(System.nanoTime() - start);
                }
            });
        }

        /** Waits for everything queued so far, so a commit still in flight is counted rather than lost. */
        void drain() {
            try {
                delegate.submit(() -> { }).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        long busyNanosAndReset() {
            return busyNanos.getAndSet(0);
        }

        void shutdown() {
            delegate.shutdownNow();
        }
    }

    /** What one bulk of recordings cost, per event, split by which thread paid for it. */
    private static final class BulkCost {
        final double caller;
        final double deferred;

        BulkCost(double caller, double deferred) {
            this.caller = caller;
            this.deferred = deferred;
        }

        double total() {
            return caller + deferred;
        }
    }

    /**
     * Records {@code iterations} events per round, returning the cheapest round priced on both sides of the
     * hand-off.
     * <p>
     * {@code newRound} gets a fresh store, so that a log file growing across the rounds cannot make the later
     * ones look worse than the earlier ones.
     */
    private BulkCost measureBulk(int iterations, TimingExecutor commits, Runnable newRound, IntConsumer record) {
        // Warmed against a store of its own, because a JVM measured cold reports the interpreter rather than
        // the compiled code, and because what the warmup stages should not land in a measured round.
        //
        // A fixed count rather than a share of the round. What the JIT needs to compile this loop has nothing
        // to do with how many events the round is going to record, and scaling the warmup down with the round
        // is what made the hundred-event row report the interpreter while the ten-thousand-event row did not.
        newRound.run();
        for (int i = 0; i < JIT_WARMUP; i++) {
            record.accept(i);
        }
        commits.drain();
        commits.busyNanosAndReset();

        BulkCost best = null;
        for (int round = 0; round < ROUNDS; round++) {
            newRound.run();
            commits.drain();
            // Whatever setting the round up cost belongs to no round.
            commits.busyNanosAndReset();

            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                record.accept(i);
            }
            long callerNanos = System.nanoTime() - start;

            // Drained before reading, so a commit still in flight when the loop ended is charged to the
            // executor rather than escaping the figure entirely.
            commits.drain();
            long deferredNanos = commits.busyNanosAndReset();

            BulkCost cost = new BulkCost((double) callerNanos / iterations,
                    (double) deferredNanos / iterations);
            if (best == null || cost.total() < best.total()) {
                best = cost;
            }
        }
        return best;
    }

    /** How long one call took, across many of them, when the tail is the point. */
    private static final class Distribution {
        final double median;
        final double mean;
        final double p999;
        final double worst;

        Distribution(double median, double mean, double p999, double worst) {
            this.median = median;
            this.mean = mean;
            this.p999 = p999;
            this.worst = worst;
        }
    }

    /**
     * Times every call rather than the enclosing loop, so the tail survives to be reported.
     * <p>
     * One round, and the median rather than the fastest. Every other figure here reports the cheapest of
     * several rounds, on the reasoning that noise can only make a round slower -- but here an outlier is the
     * measurement, and that aggregation would throw away the answer.
     */
    private Distribution measureDistribution(int iterations, IntConsumer body) {
        int warmup = Math.max(10_000, iterations / 4);
        for (int i = 0; i < warmup; i++) {
            body.accept(i);
        }

        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            body.accept(i);
            samples[i] = System.nanoTime() - start;
        }

        long total = 0;
        for (long sample : samples) {
            total += sample;
        }
        Arrays.sort(samples);
        return new Distribution(samples[iterations / 2],
                (double) total / iterations,
                samples[Math.min(iterations - 1, (int) ((long) iterations * 999 / 1000))],
                samples[iterations - 1]);
    }

    private static final class BulkMeasurement {
        final String name;
        final BulkCost cost;

        BulkMeasurement(String name, BulkCost cost) {
            this.name = name;
            this.cost = cost;
        }
    }

    private static final class DistributionMeasurement {
        final String name;
        final Distribution distribution;

        DistributionMeasurement(String name, Distribution distribution) {
            this.name = name;
            this.distribution = distribution;
        }
    }

    private static final class Measurement {
        final String name;
        final double nanosPerOp;

        Measurement(String name, double nanosPerOp) {
            this.name = name;
            this.nanosPerOp = nanosPerOp;
        }
    }

    private static void report(String title, List<Measurement> results) {
        int width = 0;
        for (Measurement result : results) {
            width = Math.max(width, result.name.length());
        }
        StringBuilder out = new StringBuilder("\n").append(title).append('\n');
        for (Measurement result : results) {
            out.append("  ").append(pad(result.name, width)).append("  ")
                    .append(format(result.nanosPerOp)).append('\n');
        }
        System.out.print(out);
        System.out.flush();
    }

    /** Three columns rather than one, because the split is the whole point of the table. */
    private static void reportBulk(String title, List<BulkMeasurement> results) {
        int width = 0;
        for (BulkMeasurement result : results) {
            width = Math.max(width, result.name.length());
        }
        StringBuilder out = new StringBuilder("\n").append(title).append(", per event\n")
                .append("  ").append(pad("", width))
                .append("  ").append(column("caller thread"))
                .append("  ").append(column("commit thread"))
                .append("  ").append(column("total")).append('\n');
        for (BulkMeasurement result : results) {
            out.append("  ").append(pad(result.name, width))
                    .append("  ").append(format(result.cost.caller))
                    .append("  ").append(format(result.cost.deferred))
                    .append("  ").append(format(result.cost.total())).append('\n');
        }
        System.out.print(out);
        System.out.flush();
    }

    private static void reportDistribution(String title, List<DistributionMeasurement> results) {
        int width = 0;
        for (DistributionMeasurement result : results) {
            width = Math.max(width, result.name.length());
        }
        StringBuilder out = new StringBuilder("\n").append(title).append('\n')
                .append("  ").append(pad("", width))
                .append("  ").append(column("median"))
                .append("  ").append(column("mean"))
                .append("  ").append(column("p99.9"))
                .append("  ").append(column("worst")).append('\n');
        for (DistributionMeasurement result : results) {
            out.append("  ").append(pad(result.name, width))
                    .append("  ").append(format(result.distribution.median))
                    .append("  ").append(format(result.distribution.mean))
                    .append("  ").append(format(result.distribution.p999))
                    .append("  ").append(format(result.distribution.worst)).append('\n');
        }
        System.out.print(out);
        System.out.flush();
    }

    /** Renders a count with thousands separators, so a table title reads as a quantity, not a code literal. */
    private static String count(int value) {
        return String.format("%,d", value);
    }

    /** Right-aligns a column heading over the fixed width {@link #format} produces. */
    private static String column(String title) {
        StringBuilder padded = new StringBuilder();
        for (int i = title.length(); i < 11; i++) {
            padded.append(' ');
        }
        return padded.append(title).toString();
    }

    private static String pad(String value, int width) {
        StringBuilder padded = new StringBuilder(value);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    private static String format(double nanoseconds) {
        if (nanoseconds >= 1_000_000) {
            return String.format("%8.2f ms", nanoseconds / 1_000_000);
        }
        if (nanoseconds >= 1_000) {
            return String.format("%8.2f \u00b5s", nanoseconds / 1_000);
        }
        return String.format("%8.1f ns", nanoseconds);
    }
}
