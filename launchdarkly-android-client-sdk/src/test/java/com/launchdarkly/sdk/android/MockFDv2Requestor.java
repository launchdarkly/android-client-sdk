package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.fdv2.Selector;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;

/**
 * A controllable mock {@link FDv2Requestor} for unit tests.
 * <p>
 * Two usage modes:
 * <ol>
 *   <li><b>Immediate response</b>: call {@link #queueResponse} or {@link #queueError} before
 *       the poll is issued; the returned future is already done when {@code poll()} returns,
 *       so {@code doPoll()} never blocks.</li>
 *   <li><b>Deferred response</b>: call {@link #awaitNextPoll} after the poll is issued to
 *       obtain the in-flight future handle, then complete it from the test thread.</li>
 * </ol>
 */
class MockFDv2Requestor implements FDv2Requestor {

    /** Every selector passed to {@link #poll(Selector)}, in call order. */
    final BlockingQueue<Selector> receivedSelectors = new LinkedBlockingQueue<>();

    /** Each call to {@link #poll(Selector)} that has no queued immediate response is parked here. */
    private final BlockingQueue<LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse>> pendingPolls =
            new LinkedBlockingQueue<>();

    /**
     * Pre-queued immediate responses/errors.  When a poll() is issued and this queue is
     * non-empty, the first item is consumed and the returned future is already completed.
     * Items are {@link FDv2Requestor.FDv2PayloadResponse} or {@link Throwable}.
     */
    private final BlockingQueue<Object> queuedItems = new LinkedBlockingQueue<>();

    /** Number of times {@link #close()} has been called. */
    volatile int closeCount = 0;

    /** Pre-queues an immediate successful response for the next {@link #poll} call. */
    void queueResponse(FDv2Requestor.FDv2PayloadResponse response) {
        queuedItems.offer(response);
    }

    /** Pre-queues an immediate error for the next {@link #poll} call. */
    void queueError(Throwable error) {
        queuedItems.offer(error);
    }

    @Override
    public Future<FDv2Requestor.FDv2PayloadResponse> poll(@NonNull Selector selector) {
        receivedSelectors.offer(selector);
        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> future = new LDAwaitFuture<>();

        Object item = queuedItems.poll();
        if (item instanceof Throwable) {
            future.setException((Throwable) item);
        } else if (item != null) {
            @SuppressWarnings("unchecked")
            FDv2Requestor.FDv2PayloadResponse resp = (FDv2Requestor.FDv2PayloadResponse) item;
            future.set(resp);
        } else {
            pendingPolls.offer(future);
        }
        return future;
    }

    /**
     * Blocks until the next {@link #poll} call that has no queued response and returns the
     * pending future handle.  Times out after 1 second.
     */
    LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> awaitNextPoll() throws InterruptedException {
        LDAwaitFuture<FDv2Requestor.FDv2PayloadResponse> f =
                pendingPolls.poll(1, TimeUnit.SECONDS);
        assertNotNull("timed out waiting for poll()", f);
        return f;
    }

    @Override
    public void close() throws IOException {
        closeCount++;
    }
}
