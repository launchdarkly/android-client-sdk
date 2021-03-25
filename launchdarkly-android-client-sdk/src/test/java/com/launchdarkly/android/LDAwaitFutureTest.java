package com.launchdarkly.android;

import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class LDAwaitFutureTest {

    @Test
    public void defaultCancelledValueIsFalse() {
        LDAwaitFuture<Void> future = new LDAwaitFuture<>();
        assertFalse(future.isCancelled());
    }

    @Test
    public void futureStartsIncomplete() {
        LDAwaitFuture<Void> future = new LDAwaitFuture<>();
        assertFalse(future.isDone());
    }

    @Test(timeout = 500L)
    public void futureThrowsTimeoutWhenNotSet() throws ExecutionException, InterruptedException {
        LDAwaitFuture<Void> future = new LDAwaitFuture<>();
        try {
            future.get(250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
        }
    }

    @Test(timeout = 500L)
    public void futureThrowsTimeoutExceptionWithZeroTimeout() throws ExecutionException,
            InterruptedException {
        LDAwaitFuture<Void> future = new LDAwaitFuture<>();
        try {
            future.get(0, TimeUnit.SECONDS);
        } catch (TimeoutException ignored) {
        }
    }

    @Test(timeout = 500L)
    public void futureDoesNotTimeoutOnSuccessfulFuture() throws InterruptedException,
            ExecutionException, TimeoutException {
        LDAwaitFuture<Void> future = new LDAwaitFuture<>();
        future.set(null);
        future.get(0, TimeUnit.SECONDS);
    }

    @Test(timeout = 500L)
    public void futureThrowsExecutionExceptionOnFailedFuture() throws InterruptedException,
            TimeoutException {
        LDAwaitFuture<Void> future = new LDAwaitFuture<>();
        Throwable t = new Throwable();
        future.setException(t);
        try {
            future.get(0, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            assertSame(t, ex.getCause());
        }
    }

    @Test(timeout = 500L)
    public void futureGetsSuccessfulFuture() throws InterruptedException, ExecutionException {
        LDAwaitFuture<Void> future = new LDAwaitFuture<>();
        future.set(null);
        future.get();
    }

    @Test(timeout = 500L)
    public void futureWakesWaiterOnSuccess() throws Exception {
        final LDAwaitFuture<Void> future = new LDAwaitFuture<>();
        Executors.newSingleThreadScheduledExecutor().schedule(() -> future.set(null), 250, TimeUnit.MILLISECONDS);
        future.get();
    }

    @Test(timeout = 500L)
    public void futureWakesWaiterOnFailure() throws Exception {
        final Throwable t = new Throwable();
        final LDAwaitFuture<Void> future = new LDAwaitFuture<>();
        Executors.newSingleThreadScheduledExecutor().schedule(() -> future.setException(t), 250, TimeUnit.MILLISECONDS);
        try {
            future.get();
            fail("Expected ExecutionException");
        } catch (ExecutionException ex) {
            assertSame(t, ex.getCause());
        }
    }

    @Test(timeout = 500L)
    public void futureReturnsSetValue() throws ExecutionException, InterruptedException {
        Object testObject = new Object();
        LDAwaitFuture<Object> future = new LDAwaitFuture<>();
        future.set(testObject);
        assertSame(testObject, future.get());
    }

    @Test(timeout = 500L)
    public void cancellingFutureDoesNothing() throws Exception {
        final LDAwaitFuture<Void> future = new LDAwaitFuture<>();
        Executors.newSingleThreadScheduledExecutor().schedule(() -> future.set(null), 250, TimeUnit.MILLISECONDS);
        assertFalse(future.cancel(false));
        assertFalse(future.cancel(true));
        future.get();
    }
}
