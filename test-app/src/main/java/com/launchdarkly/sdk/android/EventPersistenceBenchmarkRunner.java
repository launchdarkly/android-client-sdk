package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.Event;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The instrumented {@code EventPersistenceBenchmark}, runnable from the test app's Benchmark button: same
 * corpus, same loops, same tables, without JUnit or an instrumentation runner.
 * <p>
 * In the SDK's package because what it measures, {@link OutboundEventBuffer} and {@link EventStore}, is
 * package-private there. The app and the library share one class loader, so that access holds at run time.
 * <p>
 * Kept in step with {@code src/androidTest/.../EventPersistenceBenchmark} by hand; the numbers are only
 * comparable while the two measure the same thing. This one runs in the app's own process, alongside a
 * live client, and in whatever build type was installed, so read it as ratios between rows.
 */
public final class EventPersistenceBenchmarkRunner {
    /** Told the title of each section as it starts, so the caller can show how far it has got. */
    public interface Progress {
        void section(String title);
    }

    /** One iteration of a timed loop. Not IntConsumer, which needs API 24. */
    private interface Body {
        void run(int i);
    }

    private static final String FLAG_KEY = "benchmark-flag";
    private static final int FLAG_VERSION = 7;
    private static final int VARIATION = 1;
    private static final long CREATION_DATE = 1_700_000_000_000L;

    private static final String SERIALIZED_EVENT =
            "{\"kind\":\"feature\",\"key\":\"benchmark-flag\",\"value\":true,\"default\":false,"
                    + "\"variation\":1,\"version\":7,\"creationDate\":1740000000000}";

    private static final int ROUNDS = 5;
    private static final int[] BULK_SIZES = {100, 1_000, 10_000};

    /** Consumes results so that the JIT cannot prove the work is unused and delete it. */
    private static long blackhole;

    /** Holds an allocation so that escape analysis cannot scalar-replace it. */
    private static Object escaped;

    private final File scratch;
    private final Progress progress;
    private final LDLogger logger = LDLogger.withAdapter(Logs.none(), "");
    private final StringBuilder output = new StringBuilder();
    private int folders;

    private EventPersistenceBenchmarkRunner(File scratch, Progress progress) {
        this.scratch = scratch;
        this.progress = progress;
    }

    /**
     * Runs every section, which takes from several seconds to a few minutes depending on the device. Never on
     * the main thread.
     *
     * @param scratch a directory for the stores it measures, emptied before and after
     * @return the tables, headed by a line describing the device
     */
    public static String run(File scratch, Progress progress) throws IOException {
        EventPersistenceBenchmarkRunner runner = new EventPersistenceBenchmarkRunner(scratch, progress);
        deleteContents(scratch);
        try {
            runner.output.append("device=").append(android.os.Build.MODEL)
                    .append(" manufacturer=").append(android.os.Build.MANUFACTURER)
                    .append(" abi=").append(android.os.Build.SUPPORTED_ABIS[0])
                    .append(" sdk=").append(android.os.Build.VERSION.SDK_INT)
                    .append(" release=").append(android.os.Build.VERSION.RELEASE)
                    .append('\n');
            runner.encodingCostByContextShape();
            runner.recordingCostPerCall();
            runner.commitPointOutliers();
            runner.contextComparisonCost();
            runner.summarySerializationCost();
            return runner.output.toString();
        } finally {
            deleteContents(scratch);
        }
    }

    // MARK: Sections

    private void encodingCostByContextShape() {
        progress.section("encoding cost by context shape");
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
                if (sample == null) {
                    throw new IllegalStateException("the corpus produced an event that will not serialize");
                }

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

    private void recordingCostPerCall() throws IOException {
        progress.section("recording cost per call");
        final OutboundEventBuffer buffer = makeBuffer(noRedaction());
        final EventStore store = EventStore.create(newFolder(), "benchmark-key", Integer.MAX_VALUE, true, logger);
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

    private void commitPointOutliers() throws IOException {
        final byte[] serialized = SERIALIZED_EVENT.getBytes(StandardCharsets.UTF_8);
        final OutboundEventBuffer buffer = makeBuffer(noRedaction());
        final LDContext context = makeContext(ContextShape.STUB, "benchmark-key");

        for (int calls : BULK_SIZES) {
            progress.section("commit point outliers, " + count(calls) + " calls");
            final EventStore committing = EventStore.create(newFolder(), "benchmark-key",
                    Integer.MAX_VALUE, true, logger);
            final EventStore staging = EventStore.create(newFolder(), "benchmark-key",
                    Integer.MAX_VALUE, true, logger);
            try {
                List<DistributionMeasurement> results = new ArrayList<>();

                results.add(new DistributionMeasurement("track, commits on this thread",
                        measureDistribution(calls, i -> {
                            committing.stage(serialized);
                            committing.commit();
                        })));

                results.add(new DistributionMeasurement("evaluation, staged only",
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

    private void contextComparisonCost() {
        progress.section("context comparison cost");
        List<Measurement> comparisons = new ArrayList<>();
        List<Measurement> hashing = new ArrayList<>();

        for (ContextShape shape : ContextShape.values()) {
            final LDContext context = makeContext(shape, "benchmark-key");
            final LDContext sameInstance = context;
            final LDContext rebuilt = makeContext(shape, "benchmark-key");

            // Both must be equal, or the figures below measure an early exit rather than a comparison.
            if (!context.equals(rebuilt)) {
                throw new IllegalStateException(shape.label + ": rebuilt context is not equal");
            }

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

    private void summarySerializationCost() {
        progress.section("summary serialization cost");
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
            results.add(new Measurement(flagCount + " flags: counting + serializing", countingAndSerializing));
            results.add(new Measurement(flagCount + " flags: serializing alone",
                    countingAndSerializing - countingOnly));
        }

        report("turning counters into a summary event", results);
    }

    // MARK: Corpus

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
        return new OutboundEventBuffer(privacy.allAttributesPrivate, privacy.privateAttributes,
                true, Integer.MAX_VALUE, logger);
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
     * Times {@code body}, returning nanoseconds per iteration: warmed first, then the fastest of several
     * rounds, since a collection or a descheduled thread can only make a round slower.
     */
    private static double measure(int iterations, Body body) {
        int warmup = Math.max(10_000, iterations / 4);
        for (int i = 0; i < warmup; i++) {
            body.run(i);
        }

        double best = Double.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                body.run(i);
            }
            long elapsed = System.nanoTime() - start;
            best = Math.min(best, (double) elapsed / iterations);
        }
        return best;
    }

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

    /** Times every call rather than the loop, in one round, so the tail survives to be reported. */
    private static Distribution measureDistribution(int iterations, Body body) {
        int warmup = Math.max(10_000, iterations / 4);
        for (int i = 0; i < warmup; i++) {
            body.run(i);
        }

        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            body.run(i);
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

    private void report(String title, List<Measurement> results) {
        int width = 0;
        for (Measurement result : results) {
            width = Math.max(width, result.name.length());
        }
        output.append('\n').append(title).append('\n');
        for (Measurement result : results) {
            output.append("  ").append(pad(result.name, width)).append("  ")
                    .append(format(result.nanosPerOp)).append('\n');
        }
    }

    private void reportDistribution(String title, List<DistributionMeasurement> results) {
        int width = 0;
        for (DistributionMeasurement result : results) {
            width = Math.max(width, result.name.length());
        }
        output.append('\n').append(title).append('\n')
                .append("  ").append(pad("", width))
                .append("  ").append(column("median"))
                .append("  ").append(column("mean"))
                .append("  ").append(column("p99.9"))
                .append("  ").append(column("worst")).append('\n');
        for (DistributionMeasurement result : results) {
            output.append("  ").append(pad(result.name, width))
                    .append("  ").append(format(result.distribution.median))
                    .append("  ").append(format(result.distribution.mean))
                    .append("  ").append(format(result.distribution.p999))
                    .append("  ").append(format(result.distribution.worst)).append('\n');
        }
    }

    private File newFolder() throws IOException {
        File folder = new File(scratch, "store-" + folders++);
        if (!folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("could not create " + folder.getName());
        }
        return folder;
    }

    private static void deleteContents(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                deleteContents(file);
            }
            file.delete();
        }
    }

    private static String count(int value) {
        return String.format(Locale.US, "%,d", value);
    }

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
            return String.format(Locale.US, "%8.2f ms", nanoseconds / 1_000_000);
        }
        if (nanoseconds >= 1_000) {
            return String.format(Locale.US, "%8.2f \u00b5s", nanoseconds / 1_000);
        }
        return String.format(Locale.US, "%8.1f ns", nanoseconds);
    }
}
