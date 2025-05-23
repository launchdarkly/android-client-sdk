package com.launchdarkly.sdk.android.integrations;

import java.util.Objects;

/**
 * The result applies to a single identify operation. An operation may complete
 * with an error and then later complete successfully. Only the first completion
 * will be executed in the identify series.
 * <p>
 * For example, a network issue may cause an identify to error since the SDK
 * can't refresh its cached data from the cloud at that moment, but then later
 * the when the network issue is resolved, the SDK will refresh cached data.
 */
public class IdentifySeriesResult {
    /**
     * The status an identify operation completed with.
     * <p>
     * An example in which an error may occur is lack of network connectivity
     * preventing the SDK from functioning.
     */
    public enum IdentifySeriesStatus {
        COMPLETED,
        ERROR
    }

    public final IdentifySeriesStatus status;

    public IdentifySeriesResult(IdentifySeriesStatus status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        IdentifySeriesResult other = (IdentifySeriesResult)obj;
        return status == other.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(status);
    }
}
