package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ProtocolHandler;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Base class for FDv2 polling: shared logic for issuing a poll request, processing the
 * FDv2 events through {@link FDv2ProtocolHandler}, translating the resulting changeset to
 * the Android data model, and mapping the outcome to an {@link FDv2SourceResult}.
 * <p>
 * Subclasses differ only in lifecycle: {@link FDv2PollingInitializer} runs once, while
 * {@link FDv2PollingSynchronizer} polls repeatedly on a schedule.
 */
abstract class FDv2PollingBase {
    protected final FDv2Requestor requestor;
    protected final LDLogger logger;

    FDv2PollingBase(@NonNull FDv2Requestor requestor, @NonNull LDLogger logger) {
        this.requestor = requestor;
        this.logger = logger;
    }

    protected void closeRequestor() {
        try {
            requestor.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Delegates to {@link #doPoll(FDv2Requestor, LDLogger, Selector, boolean)} using this
     * instance's requestor and logger.
     */
    protected FDv2SourceResult doPoll(@NonNull Selector selector, boolean oneShot) {
        return doPoll(requestor, logger, selector, oneShot);
    }

    /**
     * Executes a single poll and synchronously returns the corresponding {@link FDv2SourceResult}.
     * <p>
     * Exposed as a static method so that non-subclasses (e.g. {@link FDv2StreamingSynchronizer}
     * responding to {@code ping} SSE events) can reuse the same response-processing logic without
     * requiring inheritance or a dedicated instance.
     * <p>
     * Blocks until the HTTP response is available. Error mapping:
     * <ul>
     *   <li>Network / IO error → TERMINAL_ERROR for initializer (oneShot=true); INTERRUPTED
     *       for synchronizer (oneShot=false).</li>
     *   <li>Non-recoverable HTTP error (e.g. 401) → TERMINAL_ERROR for both.</li>
     *   <li>Recoverable HTTP error → TERMINAL_ERROR for initializer; INTERRUPTED for
     *       synchronizer.</li>
     *   <li>304 Not Modified → CHANGE_SET with ChangeSetType.None and empty selector.</li>
     *   <li>200 with events → processed through FDv2ProtocolHandler; CHANGESET → CHANGE_SET
     *       result; GOODBYE → goodbye result; ERROR / INTERNAL_ERROR → INTERRUPTED or
     *       TERMINAL_ERROR depending on oneShot.</li>
     * </ul>
     *
     * @param requestor the requestor to issue the poll with
     * @param logger    logger
     * @param selector  the current selector; passed as the {@code basis} query param
     * @param oneShot   true if this is an initializer call (errors map to TERMINAL_ERROR rather
     *                  than INTERRUPTED so the orchestrator does not retry this source)
     * @return the FDv2SourceResult representing the outcome
     */
    static FDv2SourceResult doPoll(
            @NonNull FDv2Requestor requestor,
            @NonNull LDLogger logger,
            @NonNull Selector selector,
            boolean oneShot) {
        FDv2Requestor.FDv2PayloadResponse response;
        try {
            Future<FDv2Requestor.FDv2PayloadResponse> future = requestor.poll(selector);
            response = future.get();
        } catch (InterruptedException e) {
            return FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(e));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException) {
                LDUtil.logExceptionAtErrorLevel(logger, cause, "Polling failed with network error");
            } else {
                LDUtil.logExceptionAtErrorLevel(logger, cause, "Polling failed");
            }
            return oneShot
                    ? FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(cause))
                    : FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(cause));
        }

        boolean fdv1Fallback = response.isFdv1Fallback();

        // 304 Not Modified: nothing changed
        if (response.getStatusCode() == 304) {
            logger.debug("Polling got 304 Not Modified");
            return FDv2SourceResult.changeSet(new ChangeSet<>(
                    ChangeSetType.None,
                    selector,
                    Collections.emptyMap(),
                    null,
                    true));
        }

        if (!response.isSuccess()) {
            int code = response.getStatusCode();
            boolean recoverable = LDUtil.isHttpErrorRecoverable(code);
            logger.error("Polling failed with HTTP {}", code);
            LDFailure failure = new LDInvalidResponseCodeFailure(
                    "Polling request failed", null, code, recoverable);
            if (oneShot || !recoverable) {
                return FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(failure), fdv1Fallback);
            } else {
                return FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), fdv1Fallback);
            }
        }

        List<FDv2Event> events = response.getEvents();
        if (events == null || events.isEmpty()) {
            logger.warn("FDv2 polling: response had no events");
            LDFailure failure = new LDFailure("FDv2 polling response contained no events",
                    LDFailure.FailureType.INVALID_RESPONSE_BODY);
            return oneShot
                    ? FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(failure), fdv1Fallback)
                    : FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), fdv1Fallback);
        }

        FDv2ProtocolHandler handler = new FDv2ProtocolHandler();
        for (FDv2Event event : events) {
            FDv2ProtocolHandler.IFDv2ProtocolAction action;
            try {
                action = handler.handleEvent(event);
            } catch (Exception e) {
                LDUtil.logExceptionAtErrorLevel(logger, e, "Protocol handler error during polling");
                LDFailure failure = new LDFailure(
                        "FDv2 protocol handler error", e, LDFailure.FailureType.INVALID_RESPONSE_BODY);
                return oneShot
                        ? FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(failure), fdv1Fallback)
                        : FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), fdv1Fallback);
            }

            switch (action.getAction()) {
                case CHANGESET: {
                    FDv2ChangeSet raw =
                            ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
                    try {
                        ChangeSet<Map<String, Flag>> changeSet =
                                FDv2ChangeSetTranslator.toChangeSet(raw, logger);
                        return FDv2SourceResult.changeSet(changeSet, fdv1Fallback);
                    } catch (SerializationException e) {
                        LDUtil.logExceptionAtErrorLevel(logger, e, "Polling failed to translate changeset");
                        LDFailure failure = new LDFailure(
                                "Failed to translate FDv2 polling changeset", e,
                                LDFailure.FailureType.INVALID_RESPONSE_BODY);
                        return oneShot
                                ? FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(failure), fdv1Fallback)
                                : FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), fdv1Fallback);
                    }
                }
                case ERROR: {
                    FDv2ProtocolHandler.FDv2ActionError error =
                            (FDv2ProtocolHandler.FDv2ActionError) action;
                    logger.error("Polling received error event: {} - {}",
                            error.getId(), error.getReason());
                    LDFailure failure = new LDFailure(
                            "Polling error: " + error.getReason(),
                            LDFailure.FailureType.UNKNOWN_ERROR);
                    return oneShot
                            ? FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(failure), fdv1Fallback)
                            : FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), fdv1Fallback);
                }
                case GOODBYE: {
                    String reason = ((FDv2ProtocolHandler.FDv2ActionGoodbye) action).getReason();
                    logger.info("Polling received GOODBYE with reason: '{}'", reason);
                    return FDv2SourceResult.status(FDv2SourceResult.Status.goodbye(reason), fdv1Fallback);
                }
                case INTERNAL_ERROR: {
                    FDv2ProtocolHandler.FDv2ActionInternalError internalError =
                            (FDv2ProtocolHandler.FDv2ActionInternalError) action;
                    logger.error("Polling protocol internal error ({}): {}",
                            internalError.getErrorType(), internalError.getMessage());
                    LDFailure failure = new LDFailure(
                            "Polling internal error: " + internalError.getMessage(),
                            LDFailure.FailureType.INVALID_RESPONSE_BODY);
                    return oneShot
                            ? FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(failure), fdv1Fallback)
                            : FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), fdv1Fallback);
                }
                case NONE:
                    break;
            }
        }

        // Events array processed without yielding a changeset
        logger.warn("FDv2 polling: response events did not produce a changeset");
        LDFailure failure = new LDFailure(
                "FDv2 polling response events produced no changeset",
                LDFailure.FailureType.INVALID_RESPONSE_BODY);
        return oneShot
                ? FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(failure), fdv1Fallback)
                : FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), fdv1Fallback);
    }
}
