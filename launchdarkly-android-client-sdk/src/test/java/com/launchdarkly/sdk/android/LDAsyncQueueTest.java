package com.launchdarkly.sdk.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LDAsyncQueueTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);

    // --- put-before-take (buffered items) ---

    @Test
    public void putThenTakeReturnsImmediately() throws ExecutionException, InterruptedException {
        LDAsyncQueue<String> queue = new LDAsyncQueue<>();
        queue.put("hello");

        Future<String> future = queue.take();
        assertTrue("future should already be done", future.isDone());
        assertEquals("hello", future.get());
    }

    @Test
    public void multiplePutsBeforeTakeReturnedInFIFOOrder() throws ExecutionException, InterruptedException {
        LDAsyncQueue<Integer> queue = new LDAsyncQueue<>();
        queue.put(1);
        queue.put(2);
        queue.put(3);

        assertEquals(Integer.valueOf(1), queue.take().get());
        assertEquals(Integer.valueOf(2), queue.take().get());
        assertEquals(Integer.valueOf(3), queue.take().get());
    }

    // --- take-before-put (pending future) ---

    @Test
    public void takeThenPutCompletesWaitingFuture() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        LDAsyncQueue<String> queue = new LDAsyncQueue<>();
        Future<String> future = queue.take();
        assertFalse("future should be pending", future.isDone());

        queue.put("world");
        assertEquals("world", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void consecutiveTakesReturnSamePendingFuture() {
        // The queue is designed for a single consumer: multiple take() calls before any put()
        // share the same pending LDAwaitFuture.
        LDAsyncQueue<String> queue = new LDAsyncQueue<>();
        Future<String> f1 = queue.take();
        Future<String> f2 = queue.take();
        assertFalse(f1.isDone());
        assertFalse(f2.isDone());
        // Both futures are the same object per the single-consumer contract.
        assertTrue("both take()s before a put() must return the same future instance", f1 == f2);
    }

    // --- cross-thread delivery ---

    @Test
    public void putFromBackgroundThreadCompletesWaitingTake() throws Exception {
        LDAsyncQueue<String> queue = new LDAsyncQueue<>();
        Future<String> future = queue.take();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
            queue.put("async");
        });

        assertEquals("async", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void takeFromBackgroundThreadReceivesBufferedItem() throws Exception {
        LDAsyncQueue<Integer> queue = new LDAsyncQueue<>();
        queue.put(42);

        Future<Integer> future = Executors.newSingleThreadExecutor().submit(
                () -> queue.take().get());

        assertEquals(Integer.valueOf(42), future.get(1, TimeUnit.SECONDS));
    }
}
