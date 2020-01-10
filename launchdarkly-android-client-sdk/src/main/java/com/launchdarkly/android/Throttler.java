package com.launchdarkly.android;

import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throttler class used to rate-limit invocations of a {@link Runnable}.
 * Uses exponential backoff with random jitter to determine delay between multiple calls.
 */
class Throttler {

    @NonNull
    private final Runnable runnable;
    private final long maxRetryTimeMs;
    private final long retryTimeMs;

    private final Random jitter;
    private final AtomicInteger attempts;
    private final AtomicBoolean maxAttemptsReached;
    private final HandlerThread handlerThread;
    private final Handler handler;
    private final Runnable attemptsResetRunnable;

    Throttler(@NonNull Runnable runnable, long retryTimeMs, long maxRetryTimeMs) {
        this.runnable = runnable;
        this.retryTimeMs = retryTimeMs;
        this.maxRetryTimeMs = maxRetryTimeMs;

        jitter = new Random();
        attempts = new AtomicInteger(0);
        maxAttemptsReached = new AtomicBoolean(false);
        handlerThread = new HandlerThread("LDThrottler");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        attemptsResetRunnable = new Runnable() {
            @Override
            public void run() {
                Throttler.this.runnable.run();
                attempts.set(0);
                maxAttemptsReached.set(false);
            }
        };
    }

    void attemptRun() {
        // First invocation is instant, as is the first invocation after throttling has ended
        if (attempts.get() == 0) {
            runnable.run();
            attempts.getAndIncrement();
            return;
        }

        long jitterVal = calculateJitterVal(attempts.getAndIncrement());

        // Once the max retry time is reached, just let it run out
        if (!maxAttemptsReached.get()) {
            if (jitterVal == maxRetryTimeMs) {
                maxAttemptsReached.set(true);
            }
            long sleepTimeMs = backoffWithJitter(jitterVal);
            handler.removeCallbacks(attemptsResetRunnable);
            handler.postDelayed(attemptsResetRunnable, sleepTimeMs);
        }
    }

    void cancel() {
        handler.removeCallbacks(attemptsResetRunnable);
    }

    long calculateJitterVal(int reconnectAttempts) {
        return Math.min(maxRetryTimeMs, retryTimeMs * pow2(reconnectAttempts));
    }

    long backoffWithJitter(long jitterVal) {
        return jitterVal / 2 + nextLong(jitter, jitterVal) / 2;
    }

    // Returns 2**k, or Integer.MAX_VALUE if 2**k would overflow
    private int pow2(int k) {
        return (k < Integer.SIZE - 1) ? (1 << k) : Integer.MAX_VALUE;
    }

    // Adapted from http://stackoverflow.com/questions/2546078/java-random-long-number-in-0-x-n-range
    // Since ThreadLocalRandom.current().nextLong(n) requires Android 5
    private long nextLong(Random rand, long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }

        long r = rand.nextLong() & Long.MAX_VALUE;
        long m = bound - 1L;
        if ((bound & m) == 0) { // i.e., bound is a power of 2
            r = (bound * r) >> (Long.SIZE - 1);
        } else {
            //noinspection StatementWithEmptyBody
            for (long u = r; u - (r = u % bound) + m < 0L; u = rand.nextLong() & Long.MAX_VALUE) ;
        }
        return r;
    }
}
