package com.launchdarkly.sdk.android;

import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Tests for {@link LDFutures}, including listener accumulation fix for anyOf when
 * one future is long-lived and the other wins each time (e.g. FDv2DataSource loop).
 */
public class LDFuturesTest {

    /**
     * When the first future is already completed, addListener runs the listener synchronously
     * before the rest of the array is filled. Without the two-pass fix, the cleanup loop in the
     * listener accesses awaitables[j] and listeners[j] for j > 0 which are still null → NPE.
     */
    @Test
    public void anyOfWhenFirstFutureAlreadyCompletedDoesNotThrowNPE() throws ExecutionException, InterruptedException {
        LDAwaitFuture<Object> alreadyCompleted = new LDAwaitFuture<>();
        alreadyCompleted.set("first");
        LDAwaitFuture<Object> neverCompletes = new LDAwaitFuture<>();

        Future<Object> race = LDFutures.anyOf(alreadyCompleted, neverCompletes);

        assertEquals("first", race.get());
    }

    /**
     * Without the fix: each anyOf(longLived, nextFuture) adds a listener to longLived.
     * When nextFuture wins, that listener is never removed, so listener count grows
     * without bound. With the fix: the winning listener removes all listeners from
     * every future, so the long-lived future's listener count stays 0.
     */
    @Test
    public void anyOfDoesNotAccumulateListenersOnLongLivedFutureWhenOtherWins() throws ExecutionException, InterruptedException {
        // Long-lived future that never completes (like conditions.getFuture() in FDv2DataSource)
        LDAwaitFuture<Object> longLived = new LDAwaitFuture<>();
        int iterations = 500;
        Object value = new Object();

        for (int i = 0; i < iterations; i++) {
            LDAwaitFuture<Object> immediate = new LDAwaitFuture<>();
            immediate.set(value);
            Future<Object> race = LDFutures.anyOf(longLived, immediate);
            assertSame(value, race.get());
        }

        // With the fix: longLived has no listeners left. Without the fix: longLived would have 500.
        assertEquals("Long-lived future must not accumulate listeners when the other future wins each time",
                0, longLived.getListenerCount());
    }
}
