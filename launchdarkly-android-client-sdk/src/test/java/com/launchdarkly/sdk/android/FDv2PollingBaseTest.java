package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.json.SerializationException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link FDv2PollingBase#doPoll(FDv2Requestor, LDLogger, Selector, boolean)}.
 * <p>
 * The mock requestor is pre-loaded with an immediate response so that {@code doPoll} returns
 * synchronously on the test thread without spawning any background threads.
 */
public class FDv2PollingBaseTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);

    private static final LDLogger LOGGER = LDLogger.none();

    // ---- JSON constants for building synthetic FDv2 event lists ----

    /**
     * An empty full-transfer payload: server-intent with xfer-full followed immediately by
     * payload-transferred.  The protocol handler returns CHANGESET after the
     * payload-transferred event.
     */
    private static final String XFER_NONE_JSON =
            "{\"events\": [" +
            "{\"event\": \"server-intent\", \"data\": {\"payloads\": [{\"id\": \"payload-1\", \"target\": 100, \"intentCode\": \"xfer-full\", \"reason\": \"payload-missing\"}]}}," +
            "{\"event\": \"payload-transferred\", \"data\": {\"state\": \"(p:payload-1:100)\", \"version\": 100}}" +
            "]}";

    /**
     * A full-transfer server-intent followed by payload-transferred (empty payload).
     * The protocol handler returns a CHANGESET action after payload-transferred.
     */
    private static final String XFER_FULL_EMPTY_JSON =
            "{\"events\": [" +
            "{\"event\": \"server-intent\", \"data\": {\"payloads\": [{\"id\": \"p1\", \"target\": 1, \"intentCode\": \"xfer-full\", \"reason\": \"\"}]}}," +
            "{\"event\": \"payload-transferred\", \"data\": {\"state\": \"test-state\", \"version\": 1}}" +
            "]}";

    /** Full-transfer server-intent with NO payload-transferred: loop ends with no changeset. */
    private static final String XFER_FULL_NO_PAYLOAD_TRANSFERRED_JSON =
            "{\"events\": [{\"event\": \"server-intent\", \"data\": " +
            "{\"payloads\": [{\"id\": \"p1\", \"target\": 1, \"intentCode\": \"xfer-full\", \"reason\": \"\"}]}}]}";

    /** Goodbye event. */
    private static final String GOODBYE_JSON =
            "{\"events\": [{\"event\": \"goodbye\", \"data\": {\"reason\": \"test-reason\", \"silent\": false}}]}";

    /** Server error event. */
    private static final String ERROR_EVENT_JSON =
            "{\"events\": [{\"event\": \"error\", \"data\": {\"id\": \"err1\", \"reason\": \"test-server-error\"}}]}";

    // ---- helpers ----

    private static List<FDv2Event> parseEvents(String json) {
        try {
            return FDv2Event.parseEventsArray(json);
        } catch (SerializationException e) {
            throw new RuntimeException("test setup failed: bad JSON", e);
        }
    }

    private static FDv2SourceResult doPoll(MockFDv2Requestor requestor, boolean oneShot) {
        return FDv2PollingBase.doPoll(requestor, LOGGER, Selector.EMPTY, oneShot);
    }

    // ---- 304 Not Modified ----

    @Test
    public void http304_returnsNoneChangeSet() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.notModified(false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertNotNull(result.getChangeSet());
        assertEquals(ChangeSetType.None, result.getChangeSet().getType());
    }

    @Test
    public void http304_preservesCurrentSelector() {
        Selector selector = Selector.make(5, "cached-state");
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.notModified(false));

        FDv2SourceResult result = FDv2PollingBase.doPoll(requestor, LOGGER, selector, false);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertNotNull(result.getChangeSet());
        assertEquals(ChangeSetType.None, result.getChangeSet().getType());
        assertEquals(selector, result.getChangeSet().getSelector());
    }

    // ---- HTTP error codes ----

    @Test
    public void recoverableHttpError_oneShot_returnsTerminalError() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.failure(500, false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    @Test
    public void recoverableHttpError_notOneShot_returnsInterrupted() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.failure(500, false));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
    }

    @Test
    public void nonRecoverableHttpError_oneShot_returnsTerminalError() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.failure(401, false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    @Test
    public void nonRecoverableHttpError_notOneShot_alsoReturnsTerminalError() {
        // Non-recoverable (e.g. 401) always maps to TERMINAL_ERROR regardless of oneShot.
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.failure(401, false));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    // ---- Network (IO) errors ----

    @Test
    public void networkError_oneShot_returnsTerminalError() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueError(new IOException("connection refused"));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    @Test
    public void networkError_notOneShot_returnsInterrupted() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueError(new IOException("connection refused"));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
    }

    // ---- Empty events array ----

    @Test
    public void emptyEventsArray_oneShot_returnsTerminalError() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                Collections.emptyList(), 200, false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    @Test
    public void emptyEventsArray_notOneShot_returnsInterrupted() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                Collections.emptyList(), 200, false));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
    }

    // ---- Events loop without a changeset ----

    @Test
    public void eventsWithNoChangesetAfterLoop_oneShot_returnsTerminalError() {
        // xfer-full server-intent without payload-transferred: loop ends with no CHANGESET.
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(XFER_FULL_NO_PAYLOAD_TRANSFERRED_JSON), 200, false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    @Test
    public void eventsWithNoChangesetAfterLoop_notOneShot_returnsInterrupted() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(XFER_FULL_NO_PAYLOAD_TRANSFERRED_JSON), 200, false));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
    }

    // ---- Goodbye event ----

    @Test
    public void goodbyeEvent_returnsGoodbye() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(GOODBYE_JSON), 200, false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.GOODBYE, result.getStatus().getState());
        assertEquals("test-reason", result.getStatus().getReason());
    }

    // ---- Server error event ----

    @Test
    public void serverErrorEvent_oneShot_returnsTerminalError() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(ERROR_EVENT_JSON), 200, false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    @Test
    public void serverErrorEvent_notOneShot_returnsInterrupted() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(ERROR_EVENT_JSON), 200, false));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
    }

    // ---- Successful changesets ----

    @Test
    public void successfulXferFull_emptyPayload_returnsChangeSet() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(XFER_NONE_JSON), 200, false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertNotNull(result.getChangeSet());
    }

    @Test
    public void successfulXferFull_withData_returnsChangeSet() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(XFER_FULL_EMPTY_JSON), 200, false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertNotNull(result.getChangeSet());
    }

    // ---- Protocol handler INTERNAL_ERROR ----

    @Test
    public void internalError_malformedPayloadTransferred_oneShot_returnsTerminalError() {
        // A payload-transferred event with the wrong field name ("states" vs "state") causes the
        // FDv2ProtocolHandler to return INTERNAL_ERROR, which doPoll maps to TERMINAL_ERROR.
        String malformedPayloadTransferredJson =
                "{\"events\": [" +
                "{\"event\": \"server-intent\", \"data\": {\"payloads\": [{\"id\": \"payload-1\", \"target\": 100, \"intentCode\": \"xfer-full\", \"reason\": \"payload-missing\"}]}}," +
                "{\"event\": \"payload-transferred\", \"data\": {\"states\": \"(p:payload-1:100)\", \"version\": 100}}" +
                "]}";

        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(malformedPayloadTransferredJson), 200, false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    @Test
    public void internalError_malformedPayloadTransferred_notOneShot_returnsInterrupted() {
        String malformedPayloadTransferredJson =
                "{\"events\": [" +
                "{\"event\": \"server-intent\", \"data\": {\"payloads\": [{\"id\": \"payload-1\", \"target\": 100, \"intentCode\": \"xfer-full\", \"reason\": \"payload-missing\"}]}}," +
                "{\"event\": \"payload-transferred\", \"data\": {\"states\": \"(p:payload-1:100)\", \"version\": 100}}" +
                "]}";

        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(malformedPayloadTransferredJson), 200, false));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
    }

    // ---- Unrecognized event type ----

    @Test
    public void unrecognizedEventType_oneShot_returnsTerminalError() {
        // An unrecognized event type is treated as NONE by the protocol handler; the events
        // loop completes without producing a changeset, which maps to TERMINAL_ERROR.
        String unknownEventJson = "{\"events\": [{\"event\": \"unrecognized-event-type\", \"data\": {}}]}";

        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(unknownEventJson), 200, false));

        FDv2SourceResult result = doPoll(requestor, true);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.TERMINAL_ERROR, result.getStatus().getState());
    }

    @Test
    public void unrecognizedEventType_notOneShot_returnsInterrupted() {
        String unknownEventJson = "{\"events\": [{\"event\": \"unrecognized-event-type\", \"data\": {}}]}";

        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(unknownEventJson), 200, false));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertNotNull(result.getStatus());
        assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
    }

    // ---- Selector is forwarded to the requestor ----

    @Test
    public void selectorIsPassedToRequestor() {
        Selector selector = Selector.make(7, "my-state");
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.notModified(false));

        FDv2PollingBase.doPoll(requestor, LOGGER, selector, true);

        assertEquals(1, requestor.receivedSelectors.size());
        assertEquals(selector, requestor.receivedSelectors.poll());
    }

    // ---- fdv1Fallback propagation ----

    @Test
    public void fdv1FallbackPropagatedOnSuccess() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(XFER_FULL_EMPTY_JSON), 200, true));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertTrue(result.isFdv1Fallback());
    }

    @Test
    public void fdv1FallbackFalseByDefault() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(XFER_FULL_EMPTY_JSON), 200, false));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertFalse(result.isFdv1Fallback());
    }

    @Test
    public void fdv1FallbackPropagatedOnHttpError() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.failure(500, true));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertTrue(result.isFdv1Fallback());
    }

    @Test
    public void fdv1FallbackPropagatedOnGoodbye() {
        MockFDv2Requestor requestor = new MockFDv2Requestor();
        requestor.queueResponse(FDv2Requestor.FDv2PayloadResponse.success(
                parseEvents(GOODBYE_JSON), 200, true));

        FDv2SourceResult result = doPoll(requestor, false);

        assertEquals(SourceResultType.STATUS, result.getResultType());
        assertEquals(SourceSignal.GOODBYE, result.getStatus().getState());
        assertTrue(result.isFdv1Fallback());
    }
}
