package com.launchdarkly.sdk.android;

import java.util.concurrent.TimeUnit;

/**
 * One delivery's pass over the batches: whether it may post while the backoff would not allow it, and, for one
 * the processor started on its own, the gap it keeps between batches.
 * <p>
 * Made by {@link DeliveryScheduler} as the pass begins, and only used on the scheduler thread that runs it.
 */
final class DeliveryRun {
    /** Whether something has happened that ends a paced run's wait for good: close, going offline, a flush. */
    interface PacingEnd {
        boolean reached();
    }

    private final boolean throughTheBackoff;
    private final long gapNanos;
    /** What {@link #awaitTurn()} waits on, which {@link DeliveryScheduler#wakePacedDelivery()} notifies. */
    private final Object turnLock;
    private final PacingEnd pacingEnd;

    private boolean paced;
    private int posts;
    private long lastPostEndedNanos;

    DeliveryRun(boolean throughTheBackoff, boolean paced, long gapMillis, Object turnLock, PacingEnd pacingEnd) {
        this.throughTheBackoff = throughTheBackoff;
        this.paced = paced;
        this.gapNanos = TimeUnit.MILLISECONDS.toNanos(gapMillis);
        this.turnLock = turnLock;
        this.pacingEnd = pacingEnd;
    }

    /** Set for close()'s final delivery, which may post while the backoff would not allow it. */
    boolean postsThroughTheBackoff() {
        return throughTheBackoff;
    }

    /**
     * Holds the next batch of a paced run until a gap has passed since the last one answered. A backlog sent
     * unasked otherwise takes the network for as long as it lasts, an {@code identify}'s flag request included.
     * The first batch of a run does not wait.
     * <p>
     * A delivery someone asked for, or close, ends the pacing, and the rest go out back to back: the delivery
     * either queues runs on this thread, behind this one. Going offline ends the wait too, but the run then
     * stops before its next post, leaving the rest for a later delivery.
     */
    void awaitTurn() {
        if (!paced || posts == 0) {
            return;
        }
        long gapEnds = lastPostEndedNanos + gapNanos;
        try {
            synchronized (turnLock) {
                while (true) {
                    if (pacingEnd.reached()) {
                        paced = false;
                        return;
                    }
                    long remaining = gapEnds - System.nanoTime();
                    if (remaining <= 0) {
                        return;
                    }
                    TimeUnit.NANOSECONDS.timedWait(turnLock, remaining);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            paced = false;
        }
    }

    /** Marks a post as answered, which is when the gap before a paced run's next one starts. */
    void postEnded() {
        if (paced) {
            posts++;
            lastPostEndedNanos = System.nanoTime();
        }
    }
}
