package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.LDConfig.Builder;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;

import java.util.HashSet;
import java.util.Set;

/**
 * Contains methods for configuring delivery of analytics events.
 * <p>
 * The SDK normally buffers analytics events and sends them to LaunchDarkly at intervals. If you want
 * to customize this behavior, create a builder with {@link Components#sendEvents()}, change its
 * properties with the methods of this class, and pass it to {@link Builder#events(ComponentConfigurer)}:
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder()
 *         .events(Components.sendEvents().capacity(500).flushIntervalMillis(2000))
 *         .build();
 * </code></pre>
 * <p>
 * Note that this class is abstract; the actual implementation is created by calling {@link Components#sendEvents()}.
 *
 * @since 3.3.0
 */
public abstract class EventProcessorBuilder implements ComponentConfigurer<EventProcessor> {
    /**
     * The default value for {@link #capacity(int)}.
     */
    public static final int DEFAULT_CAPACITY = 100;

    /**
     * The default value for {@link #diagnosticRecordingIntervalMillis(int)}: 15 minutes.
     */
    public static final int DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS = 900_000;

    /**
     * The default value for {@link #flushIntervalMillis(int)}: 30 seconds.
     */
    public static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 30_000;

    /**
     * The minimum value for {@link #diagnosticRecordingIntervalMillis(int)}: 5 minutes.
     */
    public static final int MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS = 300_000;

    protected boolean allAttributesPrivate = false;
    protected int capacity = DEFAULT_CAPACITY;
    protected int diagnosticRecordingIntervalMillis = DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS;
    protected int flushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS;
    protected Set<AttributeRef> privateAttributes;

    /**
     * Sets whether or not all optional user attributes should be hidden from LaunchDarkly.
     * <p>
     * If this is {@code true}, all user attribute values (other than the key) will be private, not just
     * the attributes specified in {@link #privateAttributes(String...)} or on a per-user basis with
     * {@link com.launchdarkly.sdk.LDUser.Builder} methods. By default, it is {@code false}.
     *
     * @param allAttributesPrivate true if all user attributes should be private
     * @return the builder
     * @see #privateAttributes(String...)
     * @see com.launchdarkly.sdk.LDUser.Builder
     */
    public EventProcessorBuilder allAttributesPrivate(boolean allAttributesPrivate) {
        this.allAttributesPrivate = allAttributesPrivate;
        return this;
    }

    /**
     * Set the capacity of the events buffer.
     * <p>
     * The client buffers up to this many events in memory before flushing. If the capacity is exceeded before
     * the buffer is flushed (see {@link #flushIntervalMillis(int)}, events will be discarded. Increasing the
     * capacity means that events are less likely to be discarded, at the cost of consuming more memory.
     * <p>
     * The default value is {@link #DEFAULT_CAPACITY}.
     *
     * @param capacity the capacity of the event buffer
     * @return the builder
     */
    public EventProcessorBuilder capacity(int capacity) {
        this.capacity = capacity;
        return this;
    }

    /**
     * Sets the interval at which periodic diagnostic data is sent.
     * <p>
     * The default value is {@link #DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS}; the minimum value is
     * {@link #MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS}. This property is ignored if
     * {@link Builder#diagnosticOptOut(boolean)} is set to {@code true}.
     *
     * @param diagnosticRecordingIntervalMillis the diagnostics interval in milliseconds
     * @return the builder
     */
    public EventProcessorBuilder diagnosticRecordingIntervalMillis(int diagnosticRecordingIntervalMillis) {
        this.diagnosticRecordingIntervalMillis = diagnosticRecordingIntervalMillis < MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS ?
                MIN_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS : diagnosticRecordingIntervalMillis;
        return this;
    }

    /**
     * Sets the interval between flushes of the event buffer.
     * <p>
     * Decreasing the flush interval means that the event buffer is less likely to reach capacity.
     * <p>
     * The default value is {@link #DEFAULT_FLUSH_INTERVAL_MILLIS}.
     *
     * @param flushIntervalMillis the flush interval in milliseconds
     * @return the builder
     */
    public EventProcessorBuilder flushIntervalMillis(int flushIntervalMillis) {
        this.flushIntervalMillis = flushIntervalMillis <= 0 ? DEFAULT_FLUSH_INTERVAL_MILLIS : flushIntervalMillis;
        return this;
    }

    /**
     * Marks a set of attribute names or subproperties as private.
     * <p>
     * Any contexts sent to LaunchDarkly with this configuration active will have attributes with these
     * names removed. This is in addition to any attributes that were marked as private for an
     * individual context with {@link com.launchdarkly.sdk.LDUser.Builder} methods.
     * <p>
     * This method replaces any previous private attributes that were set on the same builder, rather
     * than adding to them.
     *
     * @param attributeNames a set of attribute names that will be removed from context data set to LaunchDarkly
     * @return the builder
     * @see #allAttributesPrivate(boolean)
     * @see com.launchdarkly.sdk.LDUser.Builder
     */
    public EventProcessorBuilder privateAttributes(String... attributeNames) {
        privateAttributes = new HashSet<>();
        for (String a: attributeNames) {
            privateAttributes.add(AttributeRef.fromPath(a));
        }
        return this;
    }
}
