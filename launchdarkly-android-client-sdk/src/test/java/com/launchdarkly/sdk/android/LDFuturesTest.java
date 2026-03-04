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
}
