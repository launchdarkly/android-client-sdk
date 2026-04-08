package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.FDv2DataSourceConditions.ConditionType;
import com.launchdarkly.sdk.android.FDv2DataSourceConditions.Conditions;
import com.launchdarkly.sdk.android.FDv2DataSourceConditions.FallbackCondition;
import com.launchdarkly.sdk.android.FDv2DataSourceConditions.RecoveryCondition;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link FDv2DataSourceConditions}.
 */
public class FDv2DataSourceConditionsTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    // ---- helpers ----

    private static FDv2SourceResult interrupted() {
        return FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(new RuntimeException("interrupted")), false);
    }

    private static FDv2SourceResult terminalError() {
        return FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(new RuntimeException("terminal")), false);
    }

    private static FDv2SourceResult changeSet() {
        return FDv2SourceResult.changeSet(
                new ChangeSet<>(ChangeSetType.None, Selector.EMPTY, new HashMap<>(), null, true), false);
    }

    // ==== FallbackCondition ====

    @Test
    public void fallback_interruptedStartsTimer_timerFiresAsFallback() throws Exception {
        FallbackCondition condition = new FallbackCondition(executor, 0);
        LDAwaitFuture<ConditionType> future = condition.getFuture();

        condition.inform(interrupted());

        // Timer fires with delay=0; wait up to 500ms for it.
        ConditionType type = future.get(500, TimeUnit.MILLISECONDS);
        assertEquals(ConditionType.FALLBACK, type);
    }

    @Test
    public void fallback_changeSetBeforeTimerFires_cancelsTimer() throws InterruptedException, TimeoutException {
        // Use a long timeout so the timer never fires during the test.
        FallbackCondition condition = new FallbackCondition(executor, 60);
        LDAwaitFuture<ConditionType> future = condition.getFuture();

        condition.inform(interrupted()); // starts timer
        condition.inform(changeSet());   // cancels timer

        // Future must NOT complete within 100ms.
        try {
            future.get(100, TimeUnit.MILLISECONDS);
            fail("expected TimeoutException — future should not have completed");
        } catch (ExecutionException e) {
            fail("unexpected exception: " + e);
        } catch (TimeoutException expected) {
        }
    }

    @Test
    public void fallback_multipleInterrupteds_dontStartMultipleTimers() throws Exception {
        // The timer is started only on the first INTERRUPTED; subsequent ones are no-ops.
        // We use a very short timeout so the timer fires quickly, then verify the future
        // completes exactly once.
        FallbackCondition condition = new FallbackCondition(executor, 0);
        LDAwaitFuture<ConditionType> future = condition.getFuture();

        condition.inform(interrupted());
        condition.inform(interrupted());
        condition.inform(interrupted());

        ConditionType type = future.get(500, TimeUnit.MILLISECONDS);
        assertEquals(ConditionType.FALLBACK, type);
    }

    @Test
    public void fallback_close_cancelsInFlightTimer() throws InterruptedException, TimeoutException {
        FallbackCondition condition = new FallbackCondition(executor, 60);
        LDAwaitFuture<ConditionType> future = condition.getFuture();

        condition.inform(interrupted()); // starts timer
        condition.close();               // cancels it

        try {
            future.get(100, TimeUnit.MILLISECONDS);
            fail("expected TimeoutException — future should not have completed after close");
        } catch (ExecutionException e) {
            fail("unexpected exception: " + e);
        } catch (TimeoutException expected) {
        }
    }

    @Test
    public void fallback_terminalError_doesNotStartTimer() throws InterruptedException, TimeoutException {
        FallbackCondition condition = new FallbackCondition(executor, 0);
        LDAwaitFuture<ConditionType> future = condition.getFuture();

        condition.inform(terminalError()); // should NOT start timer

        try {
            future.get(100, TimeUnit.MILLISECONDS);
            fail("expected TimeoutException — terminal error must not start fallback timer");
        } catch (ExecutionException e) {
            fail("unexpected exception: " + e);
        } catch (TimeoutException expected) {
        }
    }

    @Test
    public void fallback_informWithNonInterruptedStatus_doesNotStartTimer() throws InterruptedException, TimeoutException {
        FallbackCondition condition = new FallbackCondition(executor, 0);
        LDAwaitFuture<ConditionType> future = condition.getFuture();

        condition.inform(FDv2SourceResult.status(FDv2SourceResult.Status.goodbye("bye"), false));

        try {
            future.get(100, TimeUnit.MILLISECONDS);
            fail("expected TimeoutException — goodbye must not start fallback timer");
        } catch (ExecutionException e) {
            fail("unexpected exception: " + e);
        } catch (TimeoutException expected) {
        }
    }

    // ==== RecoveryCondition ====

    @Test
    public void recovery_timerStartsImmediately_andFiresAsRecovery() throws Exception {
        RecoveryCondition condition = new RecoveryCondition(executor, 0);
        LDAwaitFuture<ConditionType> future = condition.getFuture();

        ConditionType type = future.get(500, TimeUnit.MILLISECONDS);
        assertEquals(ConditionType.RECOVERY, type);
    }

    @Test
    public void recovery_close_cancelsTimer() throws InterruptedException, TimeoutException {
        RecoveryCondition condition = new RecoveryCondition(executor, 60);
        LDAwaitFuture<ConditionType> future = condition.getFuture();

        condition.close();

        try {
            future.get(100, TimeUnit.MILLISECONDS);
            fail("expected TimeoutException — future should not complete after close");
        } catch (ExecutionException e) {
            fail("unexpected exception: " + e);
        } catch (TimeoutException expected) {
        }
    }

    @Test
    public void recovery_informDoesNothing() throws Exception {
        // inform() on RecoveryCondition is a no-op regardless of the result.
        RecoveryCondition condition = new RecoveryCondition(executor, 0);
        condition.inform(interrupted());
        condition.inform(changeSet());
        condition.inform(terminalError());

        // The future should still fire (timer was not affected by inform calls).
        ConditionType type = condition.getFuture().get(500, TimeUnit.MILLISECONDS);
        assertEquals(ConditionType.RECOVERY, type);
    }

    // ==== Conditions (wrapper) ====

    @Test
    public void conditions_empty_neverCompletes() throws InterruptedException, TimeoutException {
        Conditions conditions = new Conditions(Collections.emptyList());
        Future<Object> future = conditions.getFuture();

        assertFalse(future.isDone());
        try {
            future.get(100, TimeUnit.MILLISECONDS);
            fail("expected TimeoutException");
        } catch (ExecutionException e) {
            fail("unexpected exception: " + e);
        } catch (TimeoutException expected) {
        }
    }

    @Test
    public void conditions_firstToFire_completesConditionsFuture() throws Exception {
        FallbackCondition fallback = new FallbackCondition(executor, 0);
        RecoveryCondition recovery = new RecoveryCondition(executor, 60); // long timeout

        Conditions conditions = new Conditions(Arrays.asList(fallback, recovery));

        // Trigger the fallback timer.
        fallback.inform(interrupted());

        // The conditions future should complete with FALLBACK.
        Object result = conditions.getFuture().get(500, TimeUnit.MILLISECONDS);
        assertEquals(ConditionType.FALLBACK, result);

        conditions.close();
    }

    @Test
    public void conditions_close_closesAllUnderlyingConditions() throws InterruptedException, TimeoutException {
        FallbackCondition fallback = new FallbackCondition(executor, 60);
        RecoveryCondition recovery = new RecoveryCondition(executor, 60);

        Conditions conditions = new Conditions(Arrays.asList(fallback, recovery));
        fallback.inform(interrupted()); // starts fallback timer

        conditions.close(); // must cancel both timers

        try {
            conditions.getFuture().get(100, TimeUnit.MILLISECONDS);
            fail("expected TimeoutException — close() must cancel all timers");
        } catch (ExecutionException e) {
            fail("unexpected exception: " + e);
        } catch (TimeoutException expected) {
        }
    }

    @Test
    public void conditions_inform_forwardedToAllConditions() throws Exception {
        // FallbackCondition should start its timer when Conditions.inform(interrupted) is called.
        FallbackCondition fallback = new FallbackCondition(executor, 0);
        Conditions conditions = new Conditions(Collections.singletonList(fallback));

        conditions.inform(interrupted());

        Object result = conditions.getFuture().get(500, TimeUnit.MILLISECONDS);
        assertEquals(ConditionType.FALLBACK, result);
    }
}
