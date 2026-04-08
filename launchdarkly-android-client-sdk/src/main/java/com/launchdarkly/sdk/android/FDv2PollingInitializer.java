package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Initializer;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * FDv2 polling initializer: issues a single poll request and returns the result.
 * <p>
 * The result is a {@link FDv2SourceResult} containing either a CHANGE_SET (on success) or a
 * STATUS (on error or GOODBYE). All HTTP errors map to TERMINAL_ERROR so that the orchestrator
 * does not retry this initializer. If a non-empty selector is returned in the changeset, the
 * orchestrator considers initialization complete; an empty selector causes it to try the next
 * initializer.
 * <p>
 * {@link #close()} causes any in-flight poll to be abandoned and the returned future to complete
 * with a SHUTDOWN result.
 */
final class FDv2PollingInitializer extends FDv2PollingBase implements Initializer {
    private final SelectorSource selectorSource;
    private final Executor executor;
    private final LDAwaitFuture<FDv2SourceResult> shutdownFuture = new LDAwaitFuture<>();

    /**
     * @param requestor      the FDv2 requestor used to perform the poll
     * @param selectorSource source of the current selector
     * @param executor       executor used to run the poll task on a background thread; should use
     *                       background-priority threads
     * @param logger         logger
     */
    FDv2PollingInitializer(
            @NonNull FDv2Requestor requestor,
            @NonNull SelectorSource selectorSource,
            @NonNull Executor executor,
            @NonNull LDLogger logger) {
        super(requestor, logger);
        this.selectorSource = selectorSource;
        this.executor = executor;
    }

    @Override
    @NonNull
    public Future<FDv2SourceResult> run() {
        LDAwaitFuture<FDv2SourceResult> pollFuture = new LDAwaitFuture<>();

        executor.execute(() -> {
            try {
                FDv2SourceResult result = doPoll(selectorSource.getSelector(), true);
                pollFuture.set(result);
            } catch (RuntimeException e) {
                LDUtil.logExceptionAtErrorLevel(logger, e, "Unexpected exception in polling initializer");
                pollFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(e), false));
            }
        });

        return LDFutures.anyOf(shutdownFuture, pollFuture);
    }

    @Override
    public void close() {
        shutdownFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
        closeRequestor();
    }
}
