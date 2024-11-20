package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.AssertHelpers.assertDataSetsEqual;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.easymock.EasyMockSupport;
import org.junit.Rule;

public abstract class ContextDataManagerTestBase extends EasyMockSupport {
    protected static final String MOBILE_KEY = "mobile-key";
    protected static final LDContext INITIAL_CONTEXT = LDContext.create("initial-context");
    protected static final LDContext CONTEXT = LDContext.create("test-context");

    protected final PersistentDataStore store;
    protected final PersistentDataStoreWrapper.PerEnvironmentData environmentStore;
    protected final IEnvironmentReporter environmentReporter = new EnvironmentReporterBuilder().build();
    protected final SimpleTestTaskExecutor taskExecutor = new SimpleTestTaskExecutor();

    @Rule
    public final LogCaptureRule logging = new LogCaptureRule();

    protected ContextDataManagerTestBase() {
        store = new InMemoryPersistentDataStore();
        environmentStore = new PersistentDataStoreWrapper(store, logging.logger)
                .perEnvironmentData(MOBILE_KEY);
    }

    protected static LDContext makeContext(int index) {
        return LDContext.create("context" + index);
    }

    protected static EnvironmentData makeFlagData(int index) {
        return new DataSetBuilder()
                .add(new FlagBuilder("flag" + index).version(1).build())
                .build();
    }

    protected ContextDataManager createDataManager(int maxCachedContexts) {
        ClientContext clientContext = ClientContextImpl.fromConfig(
                new LDConfig.Builder(AutoEnvAttributes.Disabled).build(),
                "mobile-key",
                "",
                null,
                INITIAL_CONTEXT,
                logging.logger,
                null,
                environmentReporter,
                taskExecutor,
                environmentStore
        );
        return new ContextDataManager(
                clientContext,
                environmentStore,
                maxCachedContexts
        );
    }

    protected ContextDataManager createDataManager() {
        return createDataManager(1);
    }

    protected void assertContextIsCached(LDContext context, EnvironmentData expectedData) {
        String contextHash = LDUtil.urlSafeBase64HashedContextId(context);
        EnvironmentData data = environmentStore.getContextData(contextHash);
        assertNotNull("flag data for context " + contextHash + " not found in store", data);
        assertDataSetsEqual(expectedData, data);

        ContextIndex index = environmentStore.getIndex();
        assertNotNull(index);
        for (ContextIndex.IndexEntry e: index.data) {
            if (e.contextId.equals(contextHash)) {
                return;
            }
        }
        fail("context hash " + contextHash + " not found in index");
    }

    protected void assertContextIsNotCached(LDContext context) {
        String contextHash = LDUtil.urlSafeBase64HashedContextId(context);
        assertNull("flag data for " + context.getKey() + " should not have been in store",
                environmentStore.getContextData(contextHash));

        ContextIndex index = environmentStore.getIndex();
        if (index != null) {
            for (ContextIndex.IndexEntry e: index.data) {
                assertNotEquals("context hash " + contextHash + " should not have been in index",
                        contextHash, e.contextId);
            }
        }
    }
}
