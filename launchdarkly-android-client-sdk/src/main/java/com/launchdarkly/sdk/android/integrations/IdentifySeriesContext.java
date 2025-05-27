package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.LDContext;

import java.util.Objects;

/**
 * Represents parameters associated with calling identify.  An instance of this class is provided to some
 * stages of series of a {@link Hook} implementation.  For example, see {@link Hook#afterTrack(TrackSeriesContext)}
 */
public class IdentifySeriesContext {
    /**
     * The context associated with the identify operation.
     */
    public final LDContext context;

    /**
     * The timeout, in seconds, associated with the identify operation.
     */
    public final Integer timeout;

    public IdentifySeriesContext(LDContext context, Integer timeout) {
        this.context = context;
        this.timeout = timeout;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        IdentifySeriesContext other = (IdentifySeriesContext)obj;
        return
            Objects.equals(context, other.context) &&
            Objects.equals(timeout, other.timeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(context, timeout);
    }
}
