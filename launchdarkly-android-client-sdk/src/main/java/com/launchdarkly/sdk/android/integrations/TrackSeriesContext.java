package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import java.util.Objects;

/**
 * Represents parameters associated with tracking a custom event.  An instance of this class is provided to some
 * stages of series of a {@link Hook} implementation.  For example, see {@link Hook#afterTrack(TrackSeriesContext)}
 */
public class TrackSeriesContext {
    /**
     * The key for the event being tracked.
     */
    public final String key;

    /**
     * The context associated with the track operation.
     */
    public final LDContext context;

    /**
     * The data associated with the track operation.
     */
    public final LDValue data;

    /**
     * The metric value associated with the track operation.
     */
    public final Double metricValue;

    /**
     * @param key           the key for the event being tracked.
     * @param context       the context associated with the track operation.
     * @param data          the data associated with the track operation.
     * @param metricValue   the metric value associated with the track operation.
     */
    public TrackSeriesContext(String key, LDContext context, LDValue data, Double metricValue) {
        this.key = key;
        this.context = context;
        this.data = data;
        this.metricValue = metricValue;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        TrackSeriesContext other = (TrackSeriesContext)obj;
        return
            Objects.equals(key, other.key) &&
            Objects.equals(context, other.context) &&
            Objects.equals(data, other.data) &&
            Objects.equals(metricValue, other.metricValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, context, data, metricValue);
    }
}
