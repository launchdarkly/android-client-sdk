package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.AssertHelpers.assertDataSetsEqual;
import static com.launchdarkly.sdk.android.AssertHelpers.assertJsonEqual;

import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.easymock.EasyMockRule;
import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;

import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PersistentDataStoreWrapperTest extends EasyMockSupport {
    // This verifies non-platform-dependent behavior, such as what keys we store particular
    // things under, using a mock persistent storage implementation.

    private static final String MOBILE_KEY = "mobile-key-123";
    private static final String MOBILE_KEY_HASH = LDUtil.urlSafeBase64Hash(MOBILE_KEY);
    private static final String EXPECTED_GLOBAL_NAMESPACE = "LaunchDarkly";
    private static final String EXPECTED_ENVIRONMENT_NAMESPACE = "LaunchDarkly_" + MOBILE_KEY_HASH;
    private static final String CONTEXT_KEY = "context-key";
    private static final String CONTEXT_KEY_HASH = LDUtil.urlSafeBase64Hash(CONTEXT_KEY);
    private static final String CONTEXT_FINGERPRINT = "mock-context-fingerprint";
    private static final String EXPECTED_CONTEXT_FLAGS_KEY = "flags_" + CONTEXT_KEY_HASH;
    private static final String EXPECTED_CONTEXT_FINGERPRINT_KEY = "contextFingerprint_" + CONTEXT_KEY_HASH;
    private static final String EXPECTED_INDEX_KEY = "index";
    private static final String EXPECTED_GENERATED_CONTEXT_KEY_PREFIX = "anonKey_";
    private static final Flag FLAG = new Flag("flagkey", LDValue.of(true), 1,
            null, 0, false, false, null, null, null);

    private final PersistentDataStore mockPersistentStore;
    private final PersistentDataStoreWrapper wrapper;
    private final PersistentDataStoreWrapper.PerEnvironmentData envWrapper;

    @Rule
    public EasyMockRule easyMockRule = new EasyMockRule(this);

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    public PersistentDataStoreWrapperTest() {
        mockPersistentStore = strictMock(PersistentDataStore.class);
        wrapper = new PersistentDataStoreWrapper(
                mockPersistentStore,
                logging.logger
        );
        envWrapper = wrapper.perEnvironmentData(MOBILE_KEY);
    }

    @Test
    public void getContextDataForUnknownContext() {
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE,
                EXPECTED_CONTEXT_FLAGS_KEY)).andReturn(null);
        replayAll();

        assertNull(envWrapper.getContextData(CONTEXT_KEY_HASH));
        verifyAll();
        logging.assertNothingLogged();
    }

    @Test
    public void getContextDataForKnownContextWithValidData() {
        EnvironmentData expectedData = new DataSetBuilder().add(FLAG).build();
        String serializedData = expectedData.toJson();
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE,
                EXPECTED_CONTEXT_FLAGS_KEY)).andReturn(serializedData);
        replayAll();

        EnvironmentData data = envWrapper.getContextData(CONTEXT_KEY_HASH);
        verifyAll();

        assertNotNull(data);
        assertDataSetsEqual(expectedData, data);
        logging.assertNothingLogged();
    }

    @Test
    public void getContextDataWhenStoreThrowsException() {
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE,
                EXPECTED_CONTEXT_FLAGS_KEY)).andThrow(makeException());
        replayAll();

        assertNull(envWrapper.getContextData(CONTEXT_KEY_HASH));
        verifyAll();
        assertStoreErrorWasLogged();
    }

    @Test
    public void setContextData() {
        EnvironmentData data = new DataSetBuilder().add(FLAG).build();
        mockPersistentStore.setValue(EXPECTED_ENVIRONMENT_NAMESPACE,
                EXPECTED_CONTEXT_FLAGS_KEY, data.toJson());
        mockPersistentStore.setValue(EXPECTED_ENVIRONMENT_NAMESPACE,
                EXPECTED_CONTEXT_FINGERPRINT_KEY, CONTEXT_FINGERPRINT);
        expectLastCall();
        replayAll();

        envWrapper.setContextData(CONTEXT_KEY_HASH, CONTEXT_FINGERPRINT, data);
        verifyAll();
        logging.assertNothingLogged();
    }

    @Test
    public void setContextDataWhenStoreThrowsException() {
        EnvironmentData data = new DataSetBuilder().add(FLAG).build();
        mockPersistentStore.setValue(EXPECTED_ENVIRONMENT_NAMESPACE,
                EXPECTED_CONTEXT_FLAGS_KEY, data.toJson());
        mockPersistentStore.setValue(EXPECTED_ENVIRONMENT_NAMESPACE,
                EXPECTED_CONTEXT_FINGERPRINT_KEY, CONTEXT_FINGERPRINT);
        expectLastCall().andThrow(makeException());
        replayAll();

        envWrapper.setContextData(CONTEXT_KEY_HASH, CONTEXT_FINGERPRINT, data);
        verifyAll();
        assertStoreErrorWasLogged();
    }

    @Test
    public void removeContextData() {
        mockPersistentStore.setValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_CONTEXT_FLAGS_KEY, null);
        mockPersistentStore.setValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_CONTEXT_FINGERPRINT_KEY, null);
        expectLastCall();
        replayAll();

        envWrapper.removeContextData(CONTEXT_KEY_HASH);
        verifyAll();
        logging.assertNothingLogged();
    }

    @Test
    public void removeContextDataWhenStoreThrowsException() {
        mockPersistentStore.setValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_CONTEXT_FLAGS_KEY, null);
        mockPersistentStore.setValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_CONTEXT_FINGERPRINT_KEY, null);
        expectLastCall().andThrow(makeException());
        replayAll();

        envWrapper.removeContextData(CONTEXT_KEY_HASH);
        verifyAll();
        assertStoreErrorWasLogged();
    }

    @Test
    public void getIndex() {
        ContextIndex expectedIndex = new ContextIndex().updateTimestamp("user1", 1000);
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_INDEX_KEY))
                .andReturn(expectedIndex.toJson());
        replayAll();

        ContextIndex index = envWrapper.getIndex();
        verifyAll();
        assertNotNull(index);
        assertJsonEqual(expectedIndex.toJson(), index.toJson());
        logging.assertNothingLogged();
    }

    @Test
    public void getIndexNotFound() {
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_INDEX_KEY))
                .andReturn(null);
        replayAll();

        ContextIndex index = envWrapper.getIndex();
        verifyAll();
        assertNotNull(index);
        assertJsonEqual(new ContextIndex().toJson(), index.toJson());
        logging.assertNothingLogged();
    }

    @Test
    public void getIndexWhenStoreThrowsException() {
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_INDEX_KEY))
                .andThrow(makeException());
        replayAll();

        ContextIndex index = envWrapper.getIndex();
        verifyAll();
        assertNotNull(index);
        assertJsonEqual(new ContextIndex().toJson(), index.toJson());
        assertStoreErrorWasLogged();
    }

    @Test
    public void setIndex() {
        ContextIndex index = new ContextIndex().updateTimestamp("user1", 1000);
        mockPersistentStore.setValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_INDEX_KEY, index.toJson());
        expectLastCall();
        replayAll();

        envWrapper.setIndex(index);
        verifyAll();
        logging.assertNothingLogged();
    }

    @Test
    public void setIndexWhenStoreThrowsException() {
        ContextIndex index = new ContextIndex().updateTimestamp("user1", 1000);
        mockPersistentStore.setValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_INDEX_KEY, index.toJson());
        expectLastCall().andThrow(makeException());
        replayAll();

        envWrapper.setIndex(index);
        verifyAll();
        assertStoreErrorWasLogged();
    }

    @Test
    public void getLastUpdated() {
        ContextIndex expectedIndex = new ContextIndex().updateTimestamp(CONTEXT_KEY_HASH, 1000);
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_CONTEXT_FINGERPRINT_KEY)).andReturn(CONTEXT_FINGERPRINT);
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_INDEX_KEY))
                .andReturn(expectedIndex.toJson());
        replayAll();

        long lastUpdated = envWrapper.getLastUpdated(CONTEXT_KEY_HASH, CONTEXT_FINGERPRINT);
        verifyAll();
        assertEquals(lastUpdated, 1000);
    }

    @Test
    public void getLastUpdatedNoMatchingHashedContextId() {
        ContextIndex expectedIndex = new ContextIndex().updateTimestamp("ImABogusContextHash", 1000);
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_CONTEXT_FINGERPRINT_KEY)).andReturn(CONTEXT_FINGERPRINT);
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_INDEX_KEY))
                .andReturn(expectedIndex.toJson());
        replayAll();

        assertNull(envWrapper.getLastUpdated(CONTEXT_KEY_HASH, CONTEXT_FINGERPRINT));
    }

    @Test
    public void getLastUpdatedNoMatchingFingerprint() {
        ContextIndex expectedIndex = new ContextIndex().updateTimestamp(CONTEXT_KEY_HASH, 1000);
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_CONTEXT_FINGERPRINT_KEY)).andReturn(null);
        expect(mockPersistentStore.getValue(EXPECTED_ENVIRONMENT_NAMESPACE, EXPECTED_INDEX_KEY))
                .andReturn(expectedIndex.toJson());
        replayAll();

        assertNull(envWrapper.getLastUpdated(CONTEXT_KEY_HASH, CONTEXT_FINGERPRINT));
    }

    @Test
    public void getOrGenerateContextKeyExisting() {
        expect(mockPersistentStore.getValue(EXPECTED_GLOBAL_NAMESPACE,
                EXPECTED_GENERATED_CONTEXT_KEY_PREFIX + "user")).andReturn("key1");
        expect(mockPersistentStore.getValue(EXPECTED_GLOBAL_NAMESPACE,
                EXPECTED_GENERATED_CONTEXT_KEY_PREFIX + "org")).andReturn("key2");
        replayAll();

        assertEquals("key1", wrapper.getOrGenerateContextKey(ContextKind.DEFAULT));
        assertEquals("key2", wrapper.getOrGenerateContextKey(ContextKind.of("org")));
        verifyAll();
        logging.assertNothingLogged();
    }

    @Test
    public void getOrGenerateContextKeyNotFound() {
        expect(mockPersistentStore.getValue(EXPECTED_GLOBAL_NAMESPACE,
                EXPECTED_GENERATED_CONTEXT_KEY_PREFIX + "user")).andReturn(null);
        mockPersistentStore.setValue(
                eq(EXPECTED_GLOBAL_NAMESPACE),
                eq(EXPECTED_GENERATED_CONTEXT_KEY_PREFIX + "user"),
                anyString()
        );
        expectLastCall();
        replayAll();

        assertNotNull( wrapper.getOrGenerateContextKey(ContextKind.DEFAULT));
        verifyAll();
        logging.assertInfoLogged("Did not find a generated key for context kind \"user\"");
    }

    @Test
    public void getOrGenerateContextKeyWhenStoreThrowsException() {
        expect(mockPersistentStore.getValue(EXPECTED_GLOBAL_NAMESPACE,
                EXPECTED_GENERATED_CONTEXT_KEY_PREFIX + "user"))
                .andThrow(makeException());
        mockPersistentStore.setValue(
                eq(EXPECTED_GLOBAL_NAMESPACE),
                eq(EXPECTED_GENERATED_CONTEXT_KEY_PREFIX + "user"),
                anyString()
        );
        expectLastCall();
        replayAll();

        assertNotNull( wrapper.getOrGenerateContextKey(ContextKind.DEFAULT));
        verifyAll();
        assertStoreErrorWasLogged();
    }

    @Test
    public void setGeneratedContextKey() {
        mockPersistentStore.setValue(EXPECTED_GLOBAL_NAMESPACE,
                EXPECTED_GENERATED_CONTEXT_KEY_PREFIX + "user", "key1");
        expectLastCall();
        mockPersistentStore.setValue(EXPECTED_GLOBAL_NAMESPACE,
                EXPECTED_GENERATED_CONTEXT_KEY_PREFIX + "org", "key2");
        expectLastCall();
        replayAll();

        wrapper.setGeneratedContextKey(ContextKind.DEFAULT, "key1");
        wrapper.setGeneratedContextKey(ContextKind.of("org"), "key2");
        verifyAll();
        logging.assertNothingLogged();
    }

    @Test
    public void setGeneratedContextKeyWhenStoreThrowsException() {
        mockPersistentStore.setValue(EXPECTED_GLOBAL_NAMESPACE,
                EXPECTED_GENERATED_CONTEXT_KEY_PREFIX + "user", "key1");
        expectLastCall().andThrow(makeException());
        replayAll();

        wrapper.setGeneratedContextKey(ContextKind.DEFAULT, "key1");
        verifyAll();
        assertStoreErrorWasLogged();
    }

    private void assertStoreErrorWasLogged() {
        logging.assertErrorLogged("Failure in persistent data store");
    }

    private Exception makeException() {
        return new RuntimeException("sorry");
    }
}
