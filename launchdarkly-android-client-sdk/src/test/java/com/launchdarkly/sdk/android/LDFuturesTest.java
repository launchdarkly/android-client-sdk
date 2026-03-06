package com.launchdarkly.sdk.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LDFuturesTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);

    // ---- anyOf ----

    @Test
    public void anyOf_firstFutureWins() throws ExecutionException, InterruptedException {
        LDAwaitFuture<String> a = new LDAwaitFuture<>();
        LDAwaitFuture<String> b = new LDAwaitFuture<>();
        a.set("first");

        LDAwaitFuture<String> result = LDFutures.anyOf(a, b);
        assertTrue(result.isDone());
        assertEquals("first", result.get());
    }

    @Test
    public void anyOf_secondFutureWins() throws ExecutionException, InterruptedException {
        LDAwaitFuture<String> a = new LDAwaitFuture<>();
        LDAwaitFuture<String> b = new LDAwaitFuture<>();
        b.set("second");

        LDAwaitFuture<String> result = LDFutures.anyOf(a, b);
        assertTrue(result.isDone());
        assertEquals("second", result.get());
    }

    @Test
    public void anyOf_completesWhenFirstFutureCompletesLater() throws Exception {
        LDAwaitFuture<String> a = new LDAwaitFuture<>();
        LDAwaitFuture<String> b = new LDAwaitFuture<>();
        LDAwaitFuture<String> result = LDFutures.anyOf(a, b);

        assertFalse(result.isDone());

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
            a.set("late");
        });

        assertEquals("late", result.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void anyOf_propagatesExceptionFromWinner() throws InterruptedException {
        LDAwaitFuture<String> a = new LDAwaitFuture<>();
        RuntimeException cause = new RuntimeException("oops");
        a.setException(cause);

        LDAwaitFuture<String> result = LDFutures.anyOf(a);
        assertTrue(result.isDone());
        try {
            result.get();
            fail("expected ExecutionException");
        } catch (ExecutionException e) {
            assertSame(cause, e.getCause());
        }
    }

    @Test
    public void anyOf_onlyFirstWinnerIsUsed() throws ExecutionException, InterruptedException {
        LDAwaitFuture<String> a = new LDAwaitFuture<>();
        LDAwaitFuture<String> b = new LDAwaitFuture<>();
        a.set("winner");
        b.set("loser");

        LDAwaitFuture<String> result = LDFutures.anyOf(a, b);
        assertEquals("winner", result.get());
    }

    @Test
    public void anyOf_null_returnsIncompleteF() throws InterruptedException, TimeoutException {
        LDAwaitFuture<String> result = LDFutures.anyOf((LDAwaitFuture<String>[]) null);
        assertFalse(result.isDone());
        try {
            result.get(50, TimeUnit.MILLISECONDS);
            fail("should not complete");
        } catch (ExecutionException e) {
            fail("unexpected exception: " + e);
        } catch (TimeoutException expected) {
        }
    }

    @Test
    public void anyOf_emptyArray_returnsIncompleteF() throws InterruptedException, TimeoutException {
        LDAwaitFuture<String> result = LDFutures.anyOf();
        assertFalse(result.isDone());
        try {
            result.get(50, TimeUnit.MILLISECONDS);
            fail("should not complete");
        } catch (ExecutionException e) {
            fail("unexpected exception: " + e);
        } catch (TimeoutException expected) {
        }
    }

    @Test
    public void anyOf_returnTypeIsLDAwaitFuture() {
        LDAwaitFuture<String> a = new LDAwaitFuture<>();
        // Compile-time check: anyOf must return LDAwaitFuture, not just Future
        LDAwaitFuture<String> result = LDFutures.anyOf(a);
        assertTrue(result instanceof LDAwaitFuture);
    }

    @Test
    public void anyOf_returnedFutureIsUsableAsFuture() throws ExecutionException, InterruptedException {
        LDAwaitFuture<Integer> a = new LDAwaitFuture<>();
        a.set(99);

        // Assign to the plain Future interface — should compile and work
        Future<Integer> result = LDFutures.anyOf(a);
        assertEquals(Integer.valueOf(99), result.get());
    }

    @Test
    public void anyOf_singleAlreadyDoneFuture() throws ExecutionException, InterruptedException {
        LDAwaitFuture<String> a = new LDAwaitFuture<>();
        a.set("done");

        LDAwaitFuture<String> result = LDFutures.anyOf(a);
        assertTrue(result.isDone());
        assertEquals("done", result.get());
    }

    // ---- fromFuture ----

    @Test
    public void fromFuture_identityForLDAwaitFuture() {
        LDAwaitFuture<String> original = new LDAwaitFuture<>();
        LDAwaitFuture<String> wrapped = LDFutures.fromFuture(original);
        assertSame("fromFuture should return the same LDAwaitFuture instance", original, wrapped);
    }

    @Test
    public void fromFuture_nonLDAwaitFuture_completesWhenSourceCompletes() throws Exception {
        LDAwaitFuture<String> source = new LDAwaitFuture<>();
        LDAwaitFuture<String> wrapped = LDFutures.fromFuture(source);

        assertFalse(wrapped.isDone());
        source.set("value");
        assertEquals("value", wrapped.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void fromFuture_alreadyDone_completedSynchronously() throws ExecutionException, InterruptedException {
        LDSuccessFuture<String> done = new LDSuccessFuture<>("sync");
        LDAwaitFuture<String> wrapped = LDFutures.fromFuture(done);
        assertTrue(wrapped.isDone());
        assertEquals("sync", wrapped.get());
    }

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
     * When the first future is already completed, its listener runs during addListener and the
     * cleanup loop cannot remove listeners that have not been added yet. Those are added in
     * the same pass and would otherwise leak on the long-lived future.
     */
    @Test
    public void anyOfWhenFirstFutureAlreadyCompletedDoesNotLeakListenerOnOthers() throws ExecutionException, InterruptedException {
        LDAwaitFuture<Object> alreadyCompleted = new LDAwaitFuture<>();
        alreadyCompleted.set("first");
        LDAwaitFuture<Object> neverCompletes = new LDAwaitFuture<>();

        LDFutures.anyOf(alreadyCompleted, neverCompletes).get();

        assertEquals("Other future must not retain a listener when the first was already completed",
                0, neverCompletes.getListenerCount());
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
