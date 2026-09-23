package com.launchdarkly.sdk.android.integrations;

/**
 * How far the SDK goes to make a recorded event outlive the process that recorded it.
 * <p>
 * An event lives in memory until it is delivered, so a process that dies before the next flush takes
 * everything recorded since the last one — including the crash an application was reporting when it died.
 * Writing events to disk is what closes that gap: they are delivered on a later run instead of being lost.
 *
 * @see EventProcessorBuilder#eventPersistence(EventPersistence)
 */
public enum EventPersistence {
    /**
     * Events are kept in memory only, and nothing survives the process ending.
     */
    DISABLED,

    /**
     * Events are written to disk, off the thread that recorded them.
     * <p>
     * Recording stays as cheap as it is without persistence, and an event becomes durable a moment after it
     * was recorded rather than immediately. A process that dies inside that window still loses it.
     */
    DEFERRED,

    /**
     * Events are written to disk before {@code track} and {@code identify} return.
     * <p>
     * The write happens on the calling thread, costing tens to hundreds of microseconds depending on the
     * device and on how many evaluations are waiting to be encoded alongside it. In exchange there is no
     * window: a process that dies the instant after {@code track} returns still reports that event on the
     * next run. Evaluating a flag stays in memory regardless.
     * <p>
     * Because this is disk I/O on the calling thread, an application that calls {@code track} or
     * {@code identify} on the main thread with {@link android.os.StrictMode} detecting disk writes will see
     * a violation for each call. Choose {@link #DEFERRED} for those callers, which keeps these writes off
     * the calling thread.
     */
    IMMEDIATE
}
