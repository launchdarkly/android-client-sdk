package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;

import com.google.gson.Gson;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class AssertHelpers {
    private static final Gson gson = new Gson();

    public static void assertDataSetsEqual(EnvironmentData expected, EnvironmentData actual) {
        assertJsonEqual(expected.toJson(), actual.toJson());
    }

    public static void assertFlagsEqual(Flag expected, Flag actual) {
        if (expected == null) {
            assertNull(actual);
        } else {
            assertNotNull(actual);
            assertJsonEqual(gson.toJson(expected), gson.toJson(actual));
        }
    }

    public static void assertJsonEqual(String expected, String actual) {
        assertEquals(LDValue.parse(expected), LDValue.parse(actual));
    }

    // assertPolledFunctionReturnsValue is temporarily copied from java-test-helpers 2.0

    /**
     * Repeatedly calls a function until it returns a non-null value or until a timeout elapses,
     * whichever comes first.
     *
     * @param <T> the return type
     * @param timeout maximum time to wait
     * @param timeoutUnit time unit for timeout (null defaults to milliseconds)
     * @param interval how often to call the function
     * @param intervalUnit time unit for interval (null defaults to milliseconds)
     * @param fn the function to call
     * @return the function's return value
     * @throws AssertionError if the function did not return a non-null value before the timeout
     */
    public static <T> T assertPolledFunctionReturnsValue(
            long timeout,
            TimeUnit timeoutUnit,
            long interval,
            TimeUnit intervalUnit,
            Callable<T> fn
    ) {
        long deadline = System.currentTimeMillis() + timeoutUnit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            try {
                T result = fn.call();
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            try {
                Thread.sleep(intervalUnit.toMillis(interval));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("timed out after " + timeout);
    }

    public static <T> T requireValue(BlockingQueue<T> queue, int timeout, TimeUnit timeoutUnit) {
        try {
            T value = queue.poll(timeout, timeoutUnit);
            if (value == null) {
                throw new AssertionError("timed out waiting for value");
            }
            return value;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
