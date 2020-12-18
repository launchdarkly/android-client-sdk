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
    private final Runnable taskRunnable;
    private final long maxRetryTimeMs;
    private final long retryTimeMs;

    private final Random jitter = new Random();
    private final AtomicInteger attempts = new AtomicInteger(-1);
    private final AtomicBoolean queuedRun = new AtomicBoolean(false);
    private final HandlerThread handlerThread;
    private final Handler handler;
    private final Runnable resetRunnable;

    Throttler(@NonNull final Runnable runnable, long retryTimeMs, long maxRetryTimeMs) {
        this.retryTimeMs = retryTimeMs;
        this.maxRetryTimeMs = maxRetryTimeMs;

        handlerThread = new HandlerThread("LDThrottler");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        taskRunnable = new Runnable() {
            @Override
            public void run() {
                queuedRun.set(false);
                runnable.run();
            }
        };
        resetRunnable = new Runnable() {
            @Override
            public void run() {
                attempts.decrementAndGet();
            }
        };
    }

    void attemptRun() {
        int attempt = attempts.getAndIncrement();

        // Grace first run instant for client initialization
        if (attempt < 0) {
            taskRunnable.run();
            return;
        }

        // First invocation is instant, as is the first invocation after throttling has ended
        if (attempt == 0) {
            taskRunnable.run();
            handler.postDelayed(resetRunnable, retryTimeMs);
            return;
        }

        long jitterVal = calculateJitterVal(attempt);
        handler.postDelayed(resetRunnable, jitterVal);
        if (!queuedRun.getAndSet(true)) {
            handler.postDelayed(taskRunnable, backoffWithJitter(jitterVal));
        }
    }

    void cancel() {
        handler.removeCallbacks(taskRunnable);
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
