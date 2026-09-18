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
 *     LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
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
    public static final int DEFAULT_CAPACITY = 1000;

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

    /**
     * The default value for {@link #persistEvents(boolean)}: off.
     */
    public static final boolean DEFAULT_PERSIST_EVENTS = false;

    /**
     * All attributes should be treated as private
     */
    protected boolean allAttributesPrivate = false;

    /**
     * The capacity of the event buffer
     */
    protected int capacity = DEFAULT_CAPACITY;

    /**
     * The diagnostic interval in millis
     */
    protected int diagnosticRecordingIntervalMillis = DEFAULT_DIAGNOSTIC_RECORDING_INTERVAL_MILLIS;

    /**
     * The flush interval in millis
     */
    protected int flushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS;

    /**
     * Whether events are written to disk so they outlive the process
     */
    protected boolean persistEvents = DEFAULT_PERSIST_EVENTS;

    /**
     * Set of attributes by reference that will be treated as private
     */
    protected Set<AttributeRef> privateAttributes;

    /**
     * Sets whether or not all optional context attributes should be hidden from LaunchDarkly.
     * <p>
     * If this is {@code true}, all context attribute values (other than the key) will be private, not just
     * the attributes specified in {@link #privateAttributes(String...)}. By default, it is {@code false}.
     *
     * @param allAttributesPrivate true if all context attributes should be private
     * @return the builder
     * @see #privateAttributes(String...)
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
     * Sets whether recorded events are written to disk, so that they survive the process ending.
     * <p>
     * Without this, an event lives in memory until it is delivered, and a process that dies before the
     * next flush takes everything recorded since the last one. That includes the crash an application
     * was reporting when it died, which is the case this exists for. With it on, events are appended to
     * a log under the application's no-backup files directory and delivered on a later run, and a
     * {@code track} or {@code identify} is on disk before the call returns.
     * <p>
     * The cost is a write on the thread that called {@code track} or {@code identify}, measured in tens
     * to hundreds of microseconds depending on the device. Evaluating a flag stays in memory either way.
     * <p>
     * The default value is {@link #DEFAULT_PERSIST_EVENTS}.
     *
     * @param persistEvents true to write events to disk
     * @return the builder
     */
    public EventProcessorBuilder persistEvents(boolean persistEvents) {
        this.persistEvents = persistEvents;
        return this;
    }

    /**
     * Marks a set of attribute names or subproperties as private.
     * <p>
     * Any contexts sent to LaunchDarkly with this configuration active will have attributes with these
     * names removed. This is in addition to any attributes that were marked as private for an
     * individual context with {@link com.launchdarkly.sdk.ContextBuilder} methods.
     * <p>
     * This method replaces any previous private attributes that were set on the same builder, rather
     * than adding to them.
     *
     * @param attributeNames a set of attribute names that will be removed from context data set to LaunchDarkly
     * @return the builder
     * @see #allAttributesPrivate(boolean)
     */
    public EventProcessorBuilder privateAttributes(String... attributeNames) {
        privateAttributes = new HashSet<>();
        for (String a: attributeNames) {
            privateAttributes.add(AttributeRef.fromPath(a));
        }
        return this;
    }
}
