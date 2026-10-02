package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Covers what {@link EventProcessor#flushAsync()} does for an implementation that predates it and
 * has only its unbounded {@link EventProcessor#blockingFlush()}. The SDK has to be able to put a
 * deadline on such a flush, and must not tell the caller its events are safe without knowing.
 */
public class EventProcessorFlushAsyncDefaultTest {
    @Rule
    public Timeout globalTimeout = Timeout.seconds(30);

    @Test
    public void theCallersDeadlineBoundsTheWaitAndNotTheFlush() throws Exception {
        LegacyEventProcessor eventProcessor = new LegacyEventProcessor();
        Future<Boolean> delivery = eventProcessor.flushAsync();

        try {
            delivery.get(100, TimeUnit.MILLISECONDS);
            fail("the wait outlived the deadline");
        } catch (TimeoutException expected) {
            // The flush is still going, which is why this is what the caller is told.
        }
        assertFalse(eventProcessor.flushReturned.get());

        eventProcessor.letFlushFinish.countDown();
        assertTrue("the flush's own outcome was not reported",
                delivery.get(5, TimeUnit.SECONDS));
        assertTrue(eventProcessor.flushReturned.get());
    }

    /** An implementation written before {@code flushAsync} existed. */
    private static final class LegacyEventProcessor implements EventProcessor {
        final CountDownLatch letFlushFinish = new CountDownLatch(1);
        final AtomicBoolean flushReturned = new AtomicBoolean(false);

        @Override
        public void blockingFlush() {
            try {
                letFlushFinish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            flushReturned.set(true);
        }

        @Override
        public void flush() {}

        @Override
        public void setInBackground(boolean inBackground) {}

        @Override
        public void setOffline(boolean offline) {}

        @Override
        public void close() {}

        @Override
        public void recordEvaluationEvent(LDContext context, String flagKey, int flagVersion,
                                          int variation, LDValue value, EvaluationReason reason,
                                          LDValue defaultValue, boolean requireFullEvent,
                                          Long debugEventsUntilDate) {}

        @Override
        public void recordIdentifyEvent(LDContext context) {}

        @Override
        public void recordCustomEvent(LDContext context, String eventKey, LDValue data,
                                      Double metricValue) {}
    }
}
