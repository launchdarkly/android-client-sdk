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
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link SourceManager}'s handling of synchronizers that report unexpected
 * errors. Such a synchronizer is put aside for a backoff and then becomes available again.
 */
public class SourceManagerTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);

    private final FakeScheduledExecutorService executor = new FakeScheduledExecutorService();
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

    private SourceManager manager(String... names) {
        for (final String name : names) {
            slots.add(new SynchronizerFactoryWithState(() -> new NamedSynchronizer(name)));
        }
        return new SourceManager(slots, Collections.emptyList(), executor);
    }

    private static void assertFirstBackoff(long delayMillis) {
        assertTrue("delay " + delayMillis,
                delayMillis > RetryRegime.EXTENDED_INITIAL_DELAY_MILLIS / 2
                        && delayMillis <= RetryRegime.EXTENDED_INITIAL_DELAY_MILLIS);
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
        SourceManager manager = manager("a", "b");
        assertEquals("a", nextNow(manager));

        long delay = manager.backOffCurrentSynchronizer(0);
        assertFirstBackoff(delay);
        assertEquals(delay, executor.awaitScheduledDelayMillis(1000));

        assertEquals("b", nextNow(manager));
        assertEquals("b", nextNow(manager));
    }

    @Test
    public void slotReturnsWhenItsBackoffEnds() throws Exception {
        SourceManager manager = manager("a");
        assertEquals("a", nextNow(manager));
        long delay = manager.backOffCurrentSynchronizer(0);

        // With every slot backing off, the next synchronizer is not known yet.
        Future<Synchronizer> next = manager.nextAvailableSynchronizer();
        assertFalse(next.isDone());

        // The backoff ending supplies it.
        executor.advanceTime(delay);
        assertEquals("a", next.get(1, TimeUnit.SECONDS).name());
        assertEquals("a", nextNow(manager));
    }

    @Test
    public void aBackingOffSlotStillOutranksTheCurrentOneForRecovery() throws Exception {
        SourceManager manager = manager("a", "b");
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
        SourceManager manager = manager("a");
        nextNow(manager);

        // A second unexpected error without any healthy operation in between doubles the wait.
        long firstDelay = manager.backOffCurrentSynchronizer(0);
        assertFirstBackoff(firstDelay);
        manager.endBackoff(slots.get(0));
        nextNow(manager);
        long secondDelay = manager.backOffCurrentSynchronizer(0);
        assertTrue("delay " + secondDelay, secondDelay > RetryRegime.EXTENDED_INITIAL_DELAY_MILLIS);
        manager.endBackoff(slots.get(0));

        // Healthy operation for the reset threshold before the next error starts the backoff over.
        nextNow(manager);
        long healthyAt = 10_000;
        manager.recordCurrentSynchronizerHealthy(healthyAt);
        long thirdDelay = manager.backOffCurrentSynchronizer(
                healthyAt + StreamingRetryState.RESET_THRESHOLD_MILLIS);
        assertFirstBackoff(thirdDelay);
    }

    @Test
    public void closeCompletesTheWaitWithNullAndCancelsPendingBackoffs() throws Exception {
        SourceManager manager = manager("a");
        nextNow(manager);
        manager.backOffCurrentSynchronizer(0);
        Future<Synchronizer> next = manager.nextAvailableSynchronizer();
        assertFalse(next.isDone());

        manager.close();
        assertNull(next.get(1, TimeUnit.SECONDS));
        assertTrue(executor.pendingDelaysMillis().isEmpty());
        assertNull(nextNow(manager));
    }

    @Test
    public void noSynchronizersYieldsNullRightAway() throws Exception {
        assertNull(nextNow(manager()));
    }
}
