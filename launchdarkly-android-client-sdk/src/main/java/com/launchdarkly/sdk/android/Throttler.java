package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throttler class used to rate-limit invocations of a {@link Runnable}.
 * Uses exponential backoff with random jitter to determine delay between multiple calls.
 */
class Throttler {

    @NonNull
    private final Runnable taskRunnable;
    private final long retryTimeMs;
    private final long maxRetryTimeMs;

    private final SecureRandom jitter = new SecureRandom();
    private final AtomicInteger attempts = new AtomicInteger(-1);
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> taskFuture;

    Throttler(@NonNull final Runnable runnable, long retryTimeMs, long maxRetryTimeMs) {
        this.taskRunnable = runnable;
        this.retryTimeMs = retryTimeMs;
        this.maxRetryTimeMs = maxRetryTimeMs;
    }

    private void run() {
        taskRunnable.run();
    }

    synchronized void attemptRun() {
        int attempt = attempts.getAndIncrement();

        // Grace first run instant for client initialization
        if (attempt < 0) {
            taskRunnable.run();
            return;
        }

        // First invocation is instant, as is the first invocation after throttling has ended
        if (attempt == 0) {
            taskRunnable.run();
            executorService.schedule(attempts::decrementAndGet, retryTimeMs, TimeUnit.MILLISECONDS);
            return;
        }

        long jitterVal = calculateJitterVal(attempt);
        executorService.schedule(attempts::decrementAndGet, jitterVal, TimeUnit.MILLISECONDS);
        if (taskFuture == null || taskFuture.isDone()) {
            taskFuture = executorService.schedule(this::run, backoffWithJitter(jitterVal), TimeUnit.MILLISECONDS);
        }
    }

    synchronized void cancel() {
        if (taskFuture != null) {
            taskFuture.cancel(false);
        }
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
    private long nextLong(SecureRandom rand, long bound) {
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
