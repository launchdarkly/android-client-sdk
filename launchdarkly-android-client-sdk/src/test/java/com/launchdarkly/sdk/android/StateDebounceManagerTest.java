package com.launchdarkly.sdk.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StateDebounceManagerTest {

    private static final long TEST_DEBOUNCE_MS = 50;

    private final SimpleTestTaskExecutor taskExecutor = new SimpleTestTaskExecutor();

    @Before
    public void before() {
    }

    @After
    public void after() {
        taskExecutor.close();
    }

    private StateDebounceManager createManager(
            boolean networkAvailable,
            boolean foreground,
            Runnable onReconcile
    ) {
        return new StateDebounceManager(
                networkAvailable, foreground,
                taskExecutor, TEST_DEBOUNCE_MS, onReconcile
        );
    }

    @Test
    public void callbackFiredAfterDebounceWindow() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        StateDebounceManager mgr = createManager(true, true, latch::countDown);

        mgr.setNetworkAvailable(false);
        assertTrue("callback should fire within debounce window",
                latch.await(TEST_DEBOUNCE_MS * 5, TimeUnit.MILLISECONDS));

        mgr.close();
    }

    @Test
    public void callbackNotFiredBeforeDebounceWindow() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.setNetworkAvailable(false);
        Thread.sleep(TEST_DEBOUNCE_MS / 3);
        assertEquals("callback should not fire before debounce window", 0, callCount.get());

        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals(1, callCount.get());

        mgr.close();
    }

    @Test
    public void duplicateValueIsNoOp() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.setNetworkAvailable(true);
        mgr.setForeground(true);
        Thread.sleep(TEST_DEBOUNCE_MS * 3);

        assertEquals("no-op changes should not trigger callback", 0, callCount.get());

        mgr.close();
    }

    @Test
    public void timerResetsOnEachEvent() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.setNetworkAvailable(false);
        Thread.sleep(TEST_DEBOUNCE_MS / 3);
        assertEquals(0, callCount.get());

        mgr.setForeground(false);
        Thread.sleep(TEST_DEBOUNCE_MS / 3);
        assertEquals("timer should reset, callback should not fire yet", 0, callCount.get());

        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("callback should fire exactly once after final timer", 1, callCount.get());

        mgr.close();
    }

    @Test
    public void callbackReceivesLatestState() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        StateDebounceManager mgr = createManager(true, true, latch::countDown);

        mgr.setNetworkAvailable(false);
        mgr.setForeground(false);

        assertTrue(latch.await(TEST_DEBOUNCE_MS * 5, TimeUnit.MILLISECONDS));
        assertFalse("should reflect latest network state", mgr.isNetworkAvailable());
        assertFalse("should reflect latest foreground state", mgr.isForeground());

        mgr.close();
    }

    @Test
    public void multipleRapidChangesCoalesceIntoOneCallback() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.setNetworkAvailable(false);
        mgr.setNetworkAvailable(true);
        mgr.setNetworkAvailable(false);
        mgr.setForeground(false);
        mgr.setForeground(true);

        Thread.sleep(TEST_DEBOUNCE_MS * 4);
        assertEquals("rapid changes should coalesce into one callback", 1, callCount.get());
        assertTrue("final network state should be false", !mgr.isNetworkAvailable());
        assertTrue("final foreground state should be true", mgr.isForeground());

        mgr.close();
    }

    @Test
    public void closePreventsFutureCallbacks() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.setNetworkAvailable(false);
        mgr.close();

        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("callback should not fire after close()", 0, callCount.get());
    }

    @Test
    public void closeCancelsPendingTimer() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.setNetworkAvailable(false);
        Thread.sleep(TEST_DEBOUNCE_MS / 3);
        mgr.close();

        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("pending timer should be cancelled on close", 0, callCount.get());
    }

    @Test
    public void settersAfterCloseDoNotTriggerCallback() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.close();
        mgr.setNetworkAvailable(false);
        mgr.setForeground(false);

        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("setters after close should not trigger callback", 0, callCount.get());
    }

    @Test
    public void separateEventsProduceSeparateCallbacks() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.setNetworkAvailable(false);
        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals(1, callCount.get());

        mgr.setNetworkAvailable(true);
        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals(2, callCount.get());

        mgr.close();
    }
}
