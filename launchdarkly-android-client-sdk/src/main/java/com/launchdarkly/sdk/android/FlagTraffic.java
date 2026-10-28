package com.launchdarkly.sdk.android;

import java.util.concurrent.TimeUnit;

/**
 * Whether a flag request is in flight, and the waits events make for it.
 * <p>
 * Flag values decide what the application shows, and events only report on it afterwards. So at launch, and
 * whenever the SDK sends a backlog on its own, events wait for the flag request rather than compete with it for
 * the network.
 * <p>
 * Reported to from whichever thread the data source answers on, and waited on by the event processor's
 * scheduler thread.
 */
final class FlagTraffic {
    /** How long events give way. */
    static final class Timings {
        static final Timings SPECIFIED = new Timings(2_000, 10_000, 1_000, 10_000);
        /** No waits at all, for tests about something else. */
        static final Timings NONE = new Timings(0, 0, 0, 0);

        /**
         * The soonest after the SDK first goes online that a previous run's events are sent, whatever the flag
         * request is doing. It gives the application's own startup traffic the network first, and is short
         * enough for an application that dies a few seconds after every launch to still send them.
         */
        final long launchFloorMillis;
        /**
         * The longest after the SDK first goes online that a previous run's events wait for the first flag
         * request: the default connection timeout, by which a request that has not answered has most likely
         * failed.
         */
        final long launchCapMillis;
        /** The least time between one paced batch's answer and the next batch's request. */
        final long gapMillis;
        /** The longest a paced batch waits for a flag request in flight, so that a hung one cannot stop delivery. */
        final long flagRequestWaitMillis;

        Timings(long launchFloorMillis, long launchCapMillis, long gapMillis, long flagRequestWaitMillis) {
            this.launchFloorMillis = launchFloorMillis;
            this.launchCapMillis = launchCapMillis;
            this.gapMillis = gapMillis;
            this.flagRequestWaitMillis = flagRequestWaitMillis;
        }
    }

    /** Ends a wait early. Asked holding this object's lock, so it must take no lock of its own. */
    interface Stop {
        boolean now();
    }

    private final Object lock = new Object();
    // Guarded by lock.
    private boolean inFlight;
    /** How the last flag request ended, or null if none has. */
    private FlagRequestListener.Outcome lastOutcome;

    void started() {
        synchronized (lock) {
            inFlight = true;
        }
    }

    void settled(FlagRequestListener.Outcome outcome) {
        synchronized (lock) {
            inFlight = false;
            lastOutcome = outcome;
            lock.notifyAll();
        }
    }

    /** Has a wait look at its {@link Stop} again. */
    void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * Why the launch gate may open, once its floor has passed, or null while it waits on the flag request.
     *
     * @param capReached whether the cap has passed, after which it waits on nothing
     * @param capMillis the cap, for saying so
     */
    String launchGateReason(boolean capReached, long capMillis) {
        synchronized (lock) {
            if (!inFlight) {
                if (lastOutcome == FlagRequestListener.Outcome.LOADED) {
                    return "flags loaded";
                }
                if (lastOutcome == FlagRequestListener.Outcome.FAILED) {
                    return "the flag request failed";
                }
                return "no flag request at launch";
            }
            return capReached ? "waiting " + capMillis + "ms for flags" : null;
        }
    }

    /**
     * Waits until {@code gapEndsNanos}, and then for as long as a flag request is in flight, up to
     * {@code flagWaitNanos} of that.
     *
     * @return true when it is the batch's turn; false if {@code stop} ended the wait
     */
    boolean awaitTurn(long gapEndsNanos, long flagWaitNanos, Stop stop) throws InterruptedException {
        boolean waitingOnFlags = false;
        long flagWaitEndsNanos = 0;
        synchronized (lock) {
            while (true) {
                if (stop.now()) {
                    return false;
                }
                long now = System.nanoTime();
                long remaining = gapEndsNanos - now;
                if (remaining <= 0) {
                    if (!inFlight) {
                        return true;
                    }
                    if (!waitingOnFlags) {
                        waitingOnFlags = true;
                        flagWaitEndsNanos = now + flagWaitNanos;
                    }
                    remaining = flagWaitEndsNanos - now;
                    if (remaining <= 0) {
                        return true;
                    }
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
        }
    }
}
