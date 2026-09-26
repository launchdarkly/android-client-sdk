package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link SourceManager}'s handling of synchronizers that report unexpected
 * errors: they are put aside for a backoff and then become available again.
 */
public class SourceManagerTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);

    private final FakeScheduledExecutorService executor = new FakeScheduledExecutorService();

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    /** A synchronizer that never produces a result; only its name matters here. */
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

    private static SynchronizerFactoryWithState slot(final String name) {
        return new SynchronizerFactoryWithState(() -> new NamedSynchronizer(name));
    }

    private SourceManager manager(String... names) {
        SynchronizerFactoryWithState[] slots = new SynchronizerFactoryWithState[names.length];
        for (int i = 0; i < names.length; i++) {
            slots[i] = slot(names[i]);
        }
        return new SourceManager(Arrays.asList(slots), Collections.emptyList(), executor);
    }

    private static void assertFirstBackoff(long delayMillis) {
        assertTrue("delay " + delayMillis,
                delayMillis > RetryState.EXTENDED_INITIAL_DELAY_MILLIS / 2
                        && delayMillis <= RetryState.EXTENDED_INITIAL_DELAY_MILLIS);
    }

    /** Advances the clock past a backoff and waits for the slot to become available again. */
    private void endBackoff(SourceManager manager, long delayMillis) throws Exception {
        Future<Void> changed = manager.awaitAvailabilityChange();
        executor.advanceTime(delayMillis);
        changed.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void backingOffASlotSkipsItUntilTheBackoffEnds() throws Exception {
        SourceManager manager = manager("a", "b");
        assertEquals("a", manager.getNextAvailableSynchronizerAndSetActive().name());

        // Backing off the current slot schedules its return and makes selection skip it.
        long delay = manager.backOffCurrentSynchronizer("a", 0);
        assertFirstBackoff(delay);
        assertEquals(delay, executor.awaitScheduledDelayMillis(1000));
        assertTrue(manager.hasBackingOffSynchronizers());
        assertEquals("b", manager.getNextAvailableSynchronizerAndSetActive().name());
        assertEquals("b", manager.getNextAvailableSynchronizerAndSetActive().name());

        // Once the backoff ends, the slot is selected again.
        endBackoff(manager, delay);
        assertFalse(manager.hasBackingOffSynchronizers());
        assertEquals("a", manager.getNextAvailableSynchronizerAndSetActive().name());
    }

    @Test
    public void allSlotsBackingOffYieldsNoSynchronizerUntilOneReturns() throws Exception {
        SourceManager manager = manager("a", "b");
        manager.getNextAvailableSynchronizerAndSetActive();
        long firstDelay = manager.backOffCurrentSynchronizer("a", 0);
        manager.getNextAvailableSynchronizerAndSetActive();
        long secondDelay = manager.backOffCurrentSynchronizer("b", 0);

        // Nothing can be selected, but the manager still knows a synchronizer will return.
        assertNull(manager.getNextAvailableSynchronizerAndSetActive());
        assertTrue(manager.hasBackingOffSynchronizers());

        // The first backoff to end makes its slot available.
        endBackoff(manager, Math.max(firstDelay, secondDelay));
        assertNotNull(manager.getNextAvailableSynchronizerAndSetActive());
    }

    @Test
    public void aBackingOffSlotStillOutranksTheCurrentOneForRecovery() throws Exception {
        SourceManager manager = manager("a", "b");
        manager.getNextAvailableSynchronizerAndSetActive();
        long delay = manager.backOffCurrentSynchronizer("a", 0);
        assertEquals("b", manager.getNextAvailableSynchronizerAndSetActive().name());

        // While "a" is backing off, "b" is not prime, but there is nothing to recover to yet.
        assertFalse(manager.isPrimeSynchronizer());
        assertFalse(manager.hasAvailableSynchronizerBeforeCurrent());

        // Once "a" is available again, recovery to it is possible.
        endBackoff(manager, delay);
        assertTrue(manager.hasAvailableSynchronizerBeforeCurrent());
    }

    @Test
    public void repeatedUnexpectedErrorsDoubleTheBackoffUntilHealthyOperationResetsIt() throws Exception {
        SourceManager manager = manager("a");
        manager.getNextAvailableSynchronizerAndSetActive();

        // A second unexpected error without any healthy operation in between doubles the wait.
        long firstDelay = manager.backOffCurrentSynchronizer("a", 0);
        assertFirstBackoff(firstDelay);
        endBackoff(manager, firstDelay);
        manager.getNextAvailableSynchronizerAndSetActive();
        long secondDelay = manager.backOffCurrentSynchronizer("a", 0);
        assertTrue("delay " + secondDelay, secondDelay > RetryState.EXTENDED_INITIAL_DELAY_MILLIS);
        endBackoff(manager, secondDelay);

        // Healthy operation for the reset threshold before the next error starts the backoff over.
        manager.getNextAvailableSynchronizerAndSetActive();
        long healthyAt = 10_000;
        manager.recordCurrentSynchronizerHealthy(healthyAt);
        long thirdDelay = manager.backOffCurrentSynchronizer("a", healthyAt + RetryState.STREAMING_RESET_THRESHOLD_MILLIS);
        assertFirstBackoff(thirdDelay);
    }

    @Test
    public void closeCancelsPendingBackoffsAndWakesWaiters() throws Exception {
        SourceManager manager = manager("a");
        manager.getNextAvailableSynchronizerAndSetActive();
        manager.backOffCurrentSynchronizer("a", 0);
        Future<Void> changed = manager.awaitAvailabilityChange();

        // Closing wakes anyone waiting for a slot and drops the scheduled return.
        manager.close();
        changed.get(1, TimeUnit.SECONDS);
        assertFalse(manager.hasBackingOffSynchronizers());
        assertTrue(executor.pendingDelaysMillis().isEmpty());
        assertNull(manager.getNextAvailableSynchronizerAndSetActive());
    }
}
