package com.launchdarkly.android;

import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LDCompletedFutureTest {
    @Test
    public void ldSuccessFuture() {
        Object contained = new Object();
        LDSuccessFuture<Object> future = new LDSuccessFuture<>(contained);
        assertFalse(future.isCancelled());
        assertTrue(future.isDone());
        assertSame(contained, future.get());
        assertSame(contained, future.get(0, TimeUnit.MILLISECONDS));
        assertFalse(future.cancel(false));
        assertFalse(future.cancel(true));
        // Still should be completed, not cancelled, and return the same value
        assertFalse(future.isCancelled());
        assertTrue(future.isDone());
        assertSame(contained, future.get());
        assertSame(contained, future.get(0, TimeUnit.MILLISECONDS));
    }

    @Test
    public void ldFailedFuture() {
        Throwable failure = new Throwable("test");
        LDFailedFuture<Object> future = new LDFailedFuture<>(failure);
        assertFalse(future.isCancelled());
        assertTrue(future.isDone());
        try {
            future.get();
            fail("Expected ExecutionException");
        } catch (ExecutionException ex) {
            assertSame(failure, ex.getCause());
        }
        try {
            future.get(0, TimeUnit.MILLISECONDS);
            fail("Expected ExecutionException");
        } catch (ExecutionException ex) {
            assertSame(failure, ex.getCause());
        }
        assertFalse(future.cancel(false));
        assertFalse(future.cancel(true));
        // Still should be completed, not cancelled, and return the same exception
        assertFalse(future.isCancelled());
        assertTrue(future.isDone());
        try {
            future.get();
            fail("Expected ExecutionException");
        } catch (ExecutionException ex) {
            assertSame(failure, ex.getCause());
        }
        try {
            future.get(0, TimeUnit.MILLISECONDS);
            fail("Expected ExecutionException");
        } catch (ExecutionException ex) {
            assertSame(failure, ex.getCause());
        }
    }
}
