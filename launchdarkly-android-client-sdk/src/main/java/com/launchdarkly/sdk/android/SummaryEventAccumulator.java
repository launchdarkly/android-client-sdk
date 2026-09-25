package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android-local counterpart of java-core's event summarizers.
 * <p>
 * Summary JSON has to be written locally for its context to use {@link ContextEncodingCache}. The
 * java-core summary's counters are package-private, so there is no seam at which Android can replace only
 * its context write. Owning the accumulator is therefore part of owning the summary writer.
 * <p>
 * The data structures deliberately mirror java-core's observable iteration behavior: flag keys and context
 * kinds use {@link HashMap}/{@link HashSet}, while versions and variations retain insertion order.
 * {@code OutboundEventBufferSerializationTest} compares the resulting bytes with
 * {@code EventOutputFormatter}; this class is allowed to move work and nothing else.
 */
final class SummaryEventAccumulator {
    private final boolean perContext;
    private final Map<LDContext, Summary> byContext;
    private Summary aggregated;

    SummaryEventAccumulator(boolean perContext) {
        this.perContext = perContext;
        this.byContext = perContext ? new HashMap<LDContext, Summary>() : null;
        this.aggregated = perContext ? null : new Summary(null);
    }

    void summarize(Event.FeatureRequest event) {
        Summary summary;
        if (perContext) {
            summary = byContext.get(event.getContext());
            if (summary == null) {
                summary = new Summary(event.getContext());
                byContext.put(event.getContext(), summary);
            }
        } else {
            summary = aggregated;
        }
        summary.add(event);
    }

    boolean isEmpty() {
        if (!perContext) {
            return aggregated.isEmpty();
        }
        for (Summary summary : byContext.values()) {
            if (!summary.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    List<Summary> getSummariesAndReset() {
        if (!perContext) {
            Summary result = aggregated;
            aggregated = new Summary(null);
            return Collections.singletonList(result);
        }

        List<Summary> results = new ArrayList<>(byContext.size());
        for (Summary summary : byContext.values()) {
            if (!summary.isEmpty()) {
                results.add(summary);
            }
        }
        byContext.clear();
        return results;
    }

    void restoreTo(List<Summary> summaries) {
        if (!perContext) {
            if (!summaries.isEmpty()) {
                aggregated = summaries.get(0);
            }
            return;
        }

        byContext.clear();
        for (Summary summary : summaries) {
            if (summary.context != null && !summary.isEmpty()) {
                byContext.put(summary.context, summary);
            }
        }
    }

    static final class Summary {
        final Map<String, FlagInfo> counters = new HashMap<>();
        final LDContext context;
        long startDate;
        long endDate;

        Summary(LDContext context) {
            this.context = context;
        }

        boolean isEmpty() {
            return counters.isEmpty();
        }

        private void add(Event.FeatureRequest event) {
            FlagInfo flag = counters.get(event.getKey());
            if (flag == null) {
                flag = new FlagInfo(event.getDefaultVal());
                counters.put(event.getKey(), flag);
            }
            for (int i = 0; i < event.getContext().getIndividualContextCount(); i++) {
                flag.contextKinds.add(event.getContext().getIndividualContext(i).getKind().toString());
            }

            IntKeyedMap<CounterValue> variations = flag.versionsAndVariations.get(event.getVersion());
            if (variations == null) {
                variations = new IntKeyedMap<>();
                flag.versionsAndVariations.put(event.getVersion(), variations);
            }
            CounterValue counter = variations.get(event.getVariation());
            if (counter == null) {
                variations.put(event.getVariation(), new CounterValue(event.getValue()));
            } else {
                counter.count++;
            }

            long timestamp = event.getCreationDate();
            if (startDate == 0 || timestamp < startDate) {
                startDate = timestamp;
            }
            if (timestamp > endDate) {
                endDate = timestamp;
            }
        }
    }

    static final class FlagInfo {
        final LDValue defaultValue;
        final IntKeyedMap<IntKeyedMap<CounterValue>> versionsAndVariations = new IntKeyedMap<>();
        final Set<String> contextKinds = new HashSet<>();

        FlagInfo(LDValue defaultValue) {
            this.defaultValue = defaultValue;
        }
    }

    static final class CounterValue {
        final LDValue value;
        long count = 1;

        CounterValue(LDValue value) {
            this.value = value;
        }
    }

    /**
     * The same primitive-key, insertion-ordered shape java-core uses for versions and variations.
     * Summary maps are tiny, so linear search avoids boxing, iterators and a hash table.
     */
    static final class IntKeyedMap<T> {
        private int[] keys = new int[4];
        private Object[] values = new Object[4];
        private int size;

        int size() {
            return size;
        }

        int keyAt(int index) {
            return keys[index];
        }

        @SuppressWarnings("unchecked")
        T valueAt(int index) {
            return (T) values[index];
        }

        @SuppressWarnings("unchecked")
        T get(int key) {
            for (int i = 0; i < size; i++) {
                if (keys[i] == key) {
                    return (T) values[i];
                }
            }
            return null;
        }

        void put(int key, T value) {
            for (int i = 0; i < size; i++) {
                if (keys[i] == key) {
                    values[i] = value;
                    return;
                }
            }
            if (size == keys.length) {
                int[] largerKeys = new int[keys.length * 2];
                Object[] largerValues = new Object[values.length * 2];
                System.arraycopy(keys, 0, largerKeys, 0, size);
                System.arraycopy(values, 0, largerValues, 0, size);
                keys = largerKeys;
                values = largerValues;
            }
            keys[size] = key;
            values[size] = value;
            size++;
        }
    }
}
