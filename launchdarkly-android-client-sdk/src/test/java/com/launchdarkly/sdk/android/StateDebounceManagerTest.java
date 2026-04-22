package com.launchdarkly.sdk.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
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
    public void multipleRapidChangesCoalesceIntoOneCallback() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.setNetworkAvailable(false);
        mgr.setNetworkAvailable(true);
        mgr.setNetworkAvailable(false);
        mgr.setForeground(false);
        mgr.setForeground(true);

        Thread.sleep(TEST_DEBOUNCE_MS * 4);
        // Final state (network=false, foreground=true) differs from initial baseline
        // (network=true, foreground=true), so the dedup allows the callback to fire.
        assertEquals("rapid changes should coalesce into one callback", 1, callCount.get());

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
    public void immediateModeFiresCallbackSynchronously() {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = new StateDebounceManager(
                true, true, taskExecutor, 0, callCount::incrementAndGet);

        mgr.setNetworkAvailable(false);
        assertEquals("callback should fire synchronously in immediate mode", 1, callCount.get());

        mgr.setForeground(false);
        assertEquals("second callback should also fire synchronously", 2, callCount.get());

        // Duplicate values should not trigger callback (per setter's same-value short-circuit).
        mgr.setNetworkAvailable(false);
        assertEquals("duplicate value should not trigger callback", 2, callCount.get());

        mgr.close();
    }

    @Test
    public void immediateModeFiresEvenWhenStateReturnsToBaseline() {
        // FDv1 immediate mode bypasses fireIfChanged dedup intentionally — the absence of a
        // debounce window means there's no "raw-state oscillation" to suppress, and dedup
        // would race on cross-thread setter calls without synchronization. Ensure that a
        // value-changing set still fires the callback even if the new value matches some
        // earlier baseline.
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = new StateDebounceManager(
                true, true, taskExecutor, 0, callCount::incrementAndGet);

        mgr.setNetworkAvailable(false); // 1
        mgr.setNetworkAvailable(true);  // 2 — back to initial baseline, but still fires
        assertEquals("immediate mode fires on each value change, no baseline dedup",
                2, callCount.get());

        mgr.close();
    }

    @Test
    public void immediateModeClosePreventsFurtherCallbacks() {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = new StateDebounceManager(
                true, true, taskExecutor, 0, callCount::incrementAndGet);

        mgr.close();
        mgr.setNetworkAvailable(false);
        assertEquals("callback should not fire after close in immediate mode", 0, callCount.get());
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

    // ==== Option A: A→B→C→A raw-state dedup at fire time ====
    //
    // These tests verify that fireIfChanged() suppresses the reconcile callback
    // when the platform churned through one or more transitions within the
    // debounce window and returned to the baseline state.

    @Test
    public void dedupSuppressesAtoBtoAWithinWindow() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        // foreground: true → false → true within the same window
        mgr.setForeground(false);
        mgr.setForeground(true);

        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("A→B→A within window should suppress the callback", 0, callCount.get());

        mgr.close();
    }

    @Test
    public void dedupSuppressesMultiAxisAtoBtoAWithinWindow() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        // Both axes oscillate and return to their baseline within the window.
        mgr.setNetworkAvailable(false);
        mgr.setForeground(false);
        mgr.setNetworkAvailable(true);
        mgr.setForeground(true);

        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("multi-axis A→B→A within window should suppress the callback",
                0, callCount.get());

        mgr.close();
    }

    @Test
    public void dedupAllowsGenuineChangeAfterSuppression() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        // First window: A→B→A, suppressed.
        mgr.setForeground(false);
        mgr.setForeground(true);
        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("first window should be suppressed", 0, callCount.get());

        // Second window: a genuine transition still fires.
        mgr.setForeground(false);
        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("subsequent genuine transition should fire", 1, callCount.get());

        mgr.close();
    }

    // ==== reset() (CONNMODE 3.5.6 — identify bypasses debounce) ====

    @Test
    public void resetCancelsPendingTimer() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.setNetworkAvailable(false);
        Thread.sleep(TEST_DEBOUNCE_MS / 3);
        mgr.reset(true, true);

        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("reset should cancel the pending timer", 0, callCount.get());

        mgr.close();
    }

    @Test
    public void resetDoesNotInvokeReconcileCallback() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        // Drive a state transition through to a successful fire.
        mgr.setNetworkAvailable(false);
        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals(1, callCount.get());

        // Reset should NOT invoke the callback even though it changes the seed.
        mgr.reset(true, true);
        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("reset itself should not fire onReconcile", 1, callCount.get());

        mgr.close();
    }

    @Test
    public void resetReseedsBaselineForNewWindow() throws InterruptedException {
        // After reset, the A→B→C→A baseline must match the new seed values, so the
        // first genuine change relative to the new seed produces a fire.
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        // Re-seed to (false, false). lastApplied also becomes (false, false).
        mgr.reset(false, false);

        // setNetworkAvailable(true): differs from new baseline → schedule + fire.
        mgr.setNetworkAvailable(true);
        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("change relative to reset seed should fire", 1, callCount.get());

        mgr.close();
    }

    @Test
    public void resetClearsAtoBtoABaseline() throws InterruptedException {
        // Without reset(), setForeground(true)→false→true within a single window is
        // dedup'd (raw state returned to baseline). After reset, the same sequence
        // measured from the NEW baseline should also be dedup'd — i.e. reset re-anchors
        // the baseline to its argument, not to the previous fire.
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.reset(false, true);

        // From baseline (false, true): toggle network true→false→true within window.
        mgr.setNetworkAvailable(true);
        mgr.setNetworkAvailable(false);
        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("A→B→A relative to reset baseline should also be suppressed",
                0, callCount.get());

        mgr.close();
    }

    @Test
    public void resetAfterCloseIsNoOp() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = createManager(true, true, callCount::incrementAndGet);

        mgr.close();
        mgr.reset(false, false);

        // Subsequent setX must not produce a fire (manager remains closed).
        mgr.setNetworkAvailable(true);
        Thread.sleep(TEST_DEBOUNCE_MS * 3);
        assertEquals("reset on a closed manager must not resurrect it", 0, callCount.get());
    }

    @Test
    public void resetInImmediateModeCancelsAndReseeds() {
        // reset() must work the same way in immediate mode (FDv1). Since immediate mode
        // has no pending timer to cancel, reset just re-seeds the mirrors. The next setX
        // relative to the new seed should fire synchronously.
        AtomicInteger callCount = new AtomicInteger(0);
        StateDebounceManager mgr = new StateDebounceManager(
                true, true, taskExecutor, 0, callCount::incrementAndGet);

        mgr.reset(false, false);
        assertEquals("reset itself should not fire in immediate mode", 0, callCount.get());

        mgr.setNetworkAvailable(true);
        assertEquals("setX after reset should fire synchronously", 1, callCount.get());

        mgr.close();
    }
}
