package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link SourceManager}'s handling of synchronizers that report unexpected
 * errors. Such a synchronizer is put aside for a backoff and then becomes available again.
 */
public class SourceManagerTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);

    // A backoff long enough that no scheduled return runs during a test, with room to double.
    private static final RetryRegime LONG_BACKOFF = new RetryRegime(60_000, 240_000);
    // A backoff short enough to wait out.
    private static final RetryRegime SHORT_BACKOFF = new RetryRegime(50, 50);

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    private final List<SynchronizerFactoryWithState> slots = new ArrayList<>();

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    /** A synchronizer that never produces a result. Only its name matters here. */
    private static final class NamedSynchronizer implements Synchronizer {
        private final String name;

        NamedSynchronizer(String name) {
            this.name = name;
        }

        @Override
        @NonNull
        public Future<FDv2SourceResult> next() {
            return new LDAwaitFuture<>();
        }

        @Override
        public void close() {}

        @Override
        @NonNull
        public String name() {
            return name;
        }
    }

    private SourceManager manager(RetryRegime backoff, String... names) {
        for (final String name : names) {
            slots.add(new SynchronizerFactoryWithState(() -> new NamedSynchronizer(name), false, backoff));
        }
        return new SourceManager(slots, Collections.emptyList(), executor);
    }

    /** Asserts that a delay is the first backoff of the regime: its initial delay, less jitter. */
    private static void assertFirstBackoff(RetryRegime regime, long delayMillis) {
        assertTrue("delay " + delayMillis,
                delayMillis > regime.initialDelayMillis / 2 && delayMillis <= regime.initialDelayMillis);
    }

    /** Selects the next synchronizer, asserting that one is known right away. */
    private static String nextNow(SourceManager manager) throws Exception {
        Future<Synchronizer> next = manager.nextAvailableSynchronizer();
        assertTrue(next.isDone());
        Synchronizer synchronizer = next.get();
        return synchronizer == null ? null : synchronizer.name();
    }

    @Test
    public void backingOffTheCurrentSlotSkipsItInFavorOfTheNext() throws Exception {
        SourceManager manager = manager(LONG_BACKOFF, "a", "b");
        assertEquals("a", nextNow(manager));

        assertFirstBackoff(LONG_BACKOFF, manager.backOffCurrentSynchronizer(0));

        assertEquals("b", nextNow(manager));
        assertEquals("b", nextNow(manager));
    }

    @Test
    public void slotReturnsWhenItsBackoffEnds() throws Exception {
        SourceManager manager = manager(SHORT_BACKOFF, "a");
        assertEquals("a", nextNow(manager));
        manager.backOffCurrentSynchronizer(0);

        // With every slot backing off, the next synchronizer is supplied when the backoff ends.
        Future<Synchronizer> next = manager.nextAvailableSynchronizer();
        assertEquals("a", next.get(2, TimeUnit.SECONDS).name());
        assertEquals("a", nextNow(manager));
    }

    @Test
    public void aBackingOffSlotStillOutranksTheCurrentOneForRecovery() throws Exception {
        SourceManager manager = manager(LONG_BACKOFF, "a", "b");
        nextNow(manager);
        manager.backOffCurrentSynchronizer(0);
        assertEquals("b", nextNow(manager));

        // While "a" is backing off, "b" is not prime, but there is nothing to recover to yet.
        assertFalse(manager.isPrimeSynchronizer());
        assertFalse(manager.hasAvailableSynchronizerBeforeCurrent());

        // Once "a" is available again, recovery to it is possible.
        manager.endBackoff(slots.get(0));
        assertTrue(manager.hasAvailableSynchronizerBeforeCurrent());
    }

    @Test
    public void repeatedUnexpectedErrorsDoubleTheBackoffUntilHealthyOperationResetsIt() throws Exception {
        SourceManager manager = manager(LONG_BACKOFF, "a");
        nextNow(manager);

        // A second unexpected error without any healthy operation in between doubles the wait.
        assertFirstBackoff(LONG_BACKOFF, manager.backOffCurrentSynchronizer(0));
        manager.endBackoff(slots.get(0));
        nextNow(manager);
        long secondDelay = manager.backOffCurrentSynchronizer(0);
        assertTrue("delay " + secondDelay, secondDelay > LONG_BACKOFF.initialDelayMillis);
        manager.endBackoff(slots.get(0));

        // Healthy operation for the reset threshold before the next error starts the backoff over.
        nextNow(manager);
        long healthyAt = 10_000;
        manager.recordCurrentSynchronizerHealthy(healthyAt);
        long thirdDelay = manager.backOffCurrentSynchronizer(
                healthyAt + StreamingRetryState.RESET_THRESHOLD_MILLIS);
        assertFirstBackoff(LONG_BACKOFF, thirdDelay);
    }

    @Test
    public void closeCompletesTheWaitWithNullAndCancelsPendingBackoffs() throws Exception {
        executor.setRemoveOnCancelPolicy(true);
        SourceManager manager = manager(LONG_BACKOFF, "a");
        nextNow(manager);
        manager.backOffCurrentSynchronizer(0);
        Future<Synchronizer> next = manager.nextAvailableSynchronizer();
        assertFalse(next.isDone());

        manager.close();
        assertNull(next.get(1, TimeUnit.SECONDS));
        assertTrue(executor.getQueue().isEmpty());
        assertNull(nextNow(manager));
    }

    @Test
    public void noSynchronizersYieldsNullRightAway() throws Exception {
        assertNull(nextNow(manager(LONG_BACKOFF)));
    }
}
