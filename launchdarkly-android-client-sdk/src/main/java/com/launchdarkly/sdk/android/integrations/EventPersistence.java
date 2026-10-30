package com.launchdarkly.sdk.android.integrations;

/**
 * How far the SDK goes to make a recorded event outlive the process that recorded it.
 * <p>
 * An event lives in memory until it is delivered, so a process that dies before the next flush takes
 * everything recorded since the last one — including the crash an application was reporting when it died.
 * Writing events to disk is what closes that gap: they are delivered on a later run instead of being lost.
 * <p>
 * {@link #DEFERRED} and {@link #IMMEDIATE} are not stable in this major version, and not subject to any
 * backwards compatibility guarantees or semantic versioning. They are experimental: their behavior, their
 * cost on the calling thread, and what they leave on disk may change in a minor release.
 *
 * @see EventProcessorBuilder#eventPersistence(EventPersistence)
 */
public enum EventPersistence {
    /**
     * Events are kept in memory only, and nothing survives the process ending.
     * <p>
     * The SDK does not touch the filesystem for events at all. Events that an earlier run with persistence
     * turned on left on disk are not delivered, and stay there until persistence is turned back on.
     */
    DISABLED,

    /**
     * Events are written to disk, off the thread that recorded them.
     * <p>
     * Recording stays as cheap as it is without persistence. Neither {@code track}, {@code identify} nor
     * evaluating a flag schedules a write of its own: events wait in memory until enough of them have built
     * up, or until the next flush, delivery, move to the background or close writes them. A process that dies
     * before then loses them.
     * <p>
     * {@code LDClient.flush} is the exception. It writes on the calling thread, as {@link #IMMEDIATE} does,
     * so that an application ending the process on purpose can rely on it. A flush on the main thread with
     * {@link android.os.StrictMode} detecting disk writes therefore sees a violation. {@code flushAndWait}
     * writes on a thread of the SDK's instead, so that its timeout bounds the write.
     * <p>
     * This level is experimental; see {@link EventPersistence}.
     */
    DEFERRED,

    /**
     * Events are written to disk before {@code track} and {@code identify} return.
     * <p>
     * The write happens on the calling thread, costing tens to hundreds of microseconds depending on the
     * device and on how many evaluations are waiting to be encoded alongside it. Evaluations are usually
     * written in the background before many build up. A burst of them faster than that can leave up to the
     * event capacity waiting, and the {@code track} or {@code identify} that follows encodes them all, which
     * can take milliseconds. In exchange there is no window: a process that dies the instant after
     * {@code track} returns still reports that event on the next run. Evaluating a flag stays in memory
     * regardless.
     * <p>
     * Because this is disk I/O on the calling thread, an application that calls {@code track} or
     * {@code identify} on the main thread with {@link android.os.StrictMode} detecting disk writes will see
     * a violation for each call. Choose {@link #DEFERRED} for those callers, which keeps these writes off
     * the calling thread.
     * <p>
     * This level is experimental; see {@link EventPersistence}.
     */
    IMMEDIATE
}
