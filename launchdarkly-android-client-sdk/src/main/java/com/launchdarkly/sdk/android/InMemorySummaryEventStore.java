package com.launchdarkly.sdk.android;

import androidx.annotation.Nullable;

import com.launchdarkly.sdk.LDValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class InMemorySummaryEventStore implements SummaryEventStore {
    private final class CounterKey {
        final String flagKey;
        final Integer flagVersion;
        final Integer variation;

        CounterKey(String flagKey, Integer flagVersion, Integer variation) {
            this.flagKey = flagKey;
            this.flagVersion = flagVersion;
            this.variation = variation;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof CounterKey) {
                CounterKey o = (CounterKey)other;
                return flagKey.equals(o.flagKey) && Objects.equals(flagVersion, o.flagVersion) &&
                        Objects.equals(variation, o.variation);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return ((flagKey.hashCode() * 31) + (flagVersion == null ? -1 : flagVersion.intValue())) * 31
                    + (variation == null ? -1 : variation.intValue());
        }
    }

    private final class CounterValue {
        final LDValue value;
        final LDValue defaultValue;
        volatile int count;

        CounterValue(LDValue value, LDValue defaultValue) {
            this.value = value;
            this.defaultValue = defaultValue;
        }
    }

    private final Map<CounterKey, CounterValue> data = new HashMap<>();
    private long startTimestamp = 0, endTimestamp = 0;

    public synchronized void clear() {
        data.clear();
        startTimestamp = endTimestamp = 0;
    }

    @Override
    public synchronized void addOrUpdateEvent(
            String flagKey,
            LDValue value,
            LDValue defaultVal,
            @Nullable Integer version,
            @Nullable Integer variation
    ) {
        long timestamp = System.currentTimeMillis();
        if (startTimestamp == 0) {
            startTimestamp = timestamp;
        }
        if (timestamp > endTimestamp) {
            endTimestamp = timestamp;
        }
        CounterKey counterKey = new CounterKey(flagKey, version, variation);
        CounterValue counterValue = data.get(counterKey);
        if (counterValue == null) {
            counterValue = new CounterValue(value, defaultVal);
            data.put(counterKey, counterValue);
        }
        counterValue.count++;
    }

    @Override
    public synchronized SummaryEvent getSummaryEvent() {
        Map<String, SummaryEventStore.FlagCounters> countersOut = new HashMap<>();
        for (Map.Entry<CounterKey, CounterValue> kv: data.entrySet()) {
            CounterKey counterKey = kv.getKey();
            String flagKey = counterKey.flagKey;
            CounterValue counterValue = kv.getValue();
            SummaryEventStore.FlagCounters countersForFlag = countersOut.get(flagKey);
            if (countersForFlag == null) {
                countersForFlag = new FlagCounters(counterValue.defaultValue);
                countersOut.put(flagKey, countersForFlag);
            }
            countersForFlag.counters.add(new FlagCounter(
                    counterValue.value,
                    counterKey.flagVersion,
                    counterKey.variation,
                    counterValue.count
            ));
        }
        return new SummaryEvent(startTimestamp, endTimestamp, countersOut);
    }

    @Override
    public synchronized SummaryEvent getSummaryEventAndClear() {
        SummaryEvent summary = getSummaryEvent();
        clear();
        return summary;
    }
}
