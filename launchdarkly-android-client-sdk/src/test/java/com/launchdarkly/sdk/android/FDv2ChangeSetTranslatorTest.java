package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2Change;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2ChangeSetType;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2ChangeType;
import com.launchdarkly.sdk.json.SerializationException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FDv2ChangeSetTranslatorTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(5);
    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    private static final LDLogger LOGGER = LDLogger.none();

    private static com.google.gson.JsonElement createFlagJsonElement(String key, int version) {
        String json = String.format(
                "{\"key\": \"%s\", \"version\": %d, \"on\": true, \"fallthrough\": {\"variation\": 0}, \"variations\": [true, false]}",
                key, version);
        return com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance().fromJson(json, com.google.gson.JsonElement.class);
    }

    @Test
    public void toChangeSet_withFullChangeset_returnsFullChangeSetType() throws SerializationException {
        List<FDv2Change> changes = Collections.singletonList(
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag1", 1, createFlagJsonElement("flag1", 1)));
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        assertEquals(ChangeSetType.Full, result.getType());
    }

    @Test
    public void toChangeSet_withPartialChangeset_returnsPartialChangeSetType() throws SerializationException {
        List<FDv2Change> changes = Collections.singletonList(
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag1", 1, createFlagJsonElement("flag1", 1)));
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.PARTIAL, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        assertEquals(ChangeSetType.Partial, result.getType());
    }

    @Test
    public void toChangeSet_withNoneChangeset_returnsNoneChangeSetType() throws SerializationException {
        List<FDv2Change> changes = Collections.emptyList();
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.NONE, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        assertEquals(ChangeSetType.None, result.getType());
    }

    @Test
    public void toChangeSet_includesSelector() throws SerializationException {
        List<FDv2Change> changes = Collections.emptyList();
        Selector selector = Selector.make(42, "test-state");
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, selector);

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        assertEquals(selector.getVersion(), result.getSelector().getVersion());
        assertEquals(selector.getState(), result.getSelector().getState());
    }

    @Test
    public void toChangeSet_withNullSelector_usesEmptySelector() throws SerializationException {
        List<FDv2Change> changes = Collections.emptyList();
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, null);

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        assertTrue(result.getSelector().isEmpty());
    }

    @Test
    public void toChangeSet_withPutOperation_deserializesFlag() throws SerializationException {
        List<FDv2Change> changes = Collections.singletonList(
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag1", 1, createFlagJsonElement("flag1", 1)));
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        assertEquals(1, result.getData().size());
        assertTrue(result.getData().containsKey("flag1"));
        Flag flag = result.getData().get("flag1");
        assertNotNull(flag);
        assertEquals("flag1", flag.getKey());
        assertEquals(1, flag.getVersion());
        assertFalse(flag.isDeleted());
    }

    @Test
    public void toChangeSet_withDeleteOperation_createsDeletedPlaceholder() throws SerializationException {
        List<FDv2Change> changes = Collections.singletonList(
                new FDv2Change(FDv2ChangeType.DELETE, "flag_eval", "flag1", 5, null));
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.PARTIAL, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        assertEquals(1, result.getData().size());
        Flag flag = result.getData().get("flag1");
        assertNotNull(flag);
        assertEquals("flag1", flag.getKey());
        assertEquals(5, flag.getVersion());
        assertTrue(flag.isDeleted());
    }

    @Test
    public void toChangeSet_withMultipleFlags_includesAll() throws SerializationException {
        List<FDv2Change> changes = Arrays.asList(
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag1", 1, createFlagJsonElement("flag1", 1)),
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag2", 2, createFlagJsonElement("flag2", 2)));
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        assertEquals(2, result.getData().size());
        assertNotNull(result.getData().get("flag1"));
        assertNotNull(result.getData().get("flag2"));
    }

    @Test
    public void toChangeSet_withNonFlagEvalKind_skipsAndIncludesOnlyFlagEval() throws SerializationException {
        List<FDv2Change> changes = Arrays.asList(
                new FDv2Change(FDv2ChangeType.PUT, "segment", "seg1", 1, createFlagJsonElement("seg1", 1)),
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag1", 1, createFlagJsonElement("flag1", 1)));
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, logging.logger);

        assertEquals(1, result.getData().size());
        assertNotNull(result.getData().get("flag1"));
        assertTrue("Expected debug log about skipped 'segment' kind",
                logging.logCapture.getMessageStrings().stream()
                        .anyMatch(s -> s.contains("DEBUG:") && s.contains("segment")));
    }

    @Test
    public void toChangeSet_withPutMissingObject_skipsAndLogsWarning() throws SerializationException {
        List<FDv2Change> changes = Arrays.asList(
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag1", 1, null),
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag2", 2, createFlagJsonElement("flag2", 2)));
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, logging.logger);

        assertEquals(1, result.getData().size());
        assertNull(result.getData().get("flag1"));
        assertNotNull(result.getData().get("flag2"));
        logging.assertWarnLogged("missing object data");
    }

    @Test
    public void toChangeSet_withEmptyChanges_returnsEmptyFlags() throws SerializationException {
        List<FDv2Change> changes = Collections.emptyList();
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        assertTrue(result.getData().isEmpty());
    }

    @Test
    public void toChangeSet_withMixedPutAndDelete_handlesAll() throws SerializationException {
        List<FDv2Change> changes = Arrays.asList(
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag1", 1, createFlagJsonElement("flag1", 1)),
                new FDv2Change(FDv2ChangeType.DELETE, "flag_eval", "flag2", 2, null));
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.PARTIAL, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        assertEquals(2, result.getData().size());
        Flag putFlag = result.getData().get("flag1");
        assertNotNull(putFlag);
        assertFalse(putFlag.isDeleted());
        Flag deleteFlag = result.getData().get("flag2");
        assertNotNull(deleteFlag);
        assertTrue(deleteFlag.isDeleted());
        assertEquals(2, deleteFlag.getVersion());
    }

    @Test
    public void toChangeSet_preservesOrderOfChangesWithinKind() throws SerializationException {
        List<FDv2Change> changes = Arrays.asList(
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag3", 3, createFlagJsonElement("flag3", 3)),
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag1", 1, createFlagJsonElement("flag1", 1)),
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag2", 2, createFlagJsonElement("flag2", 2)));
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

        ChangeSet<Map<String, Flag>> result = FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);

        List<String> keys = new ArrayList<>(result.getData().keySet());
        assertEquals("flag3", keys.get(0));
        assertEquals("flag1", keys.get(1));
        assertEquals("flag2", keys.get(2));
    }

    @Test(expected = SerializationException.class)
    public void toChangeSet_withInvalidFlagJson_throws() throws SerializationException {
        // Object that serializes to something Flag.fromJson cannot deserialize
        com.google.gson.JsonElement invalid = com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance()
                .fromJson("123", com.google.gson.JsonElement.class);
        List<FDv2Change> changes = Collections.singletonList(
                new FDv2Change(FDv2ChangeType.PUT, "flag_eval", "flag1", 1, invalid));
        FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, LOGGER);
    }
}
