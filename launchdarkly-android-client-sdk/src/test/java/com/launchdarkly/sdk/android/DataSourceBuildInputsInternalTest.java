package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuildInputs;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.fdv2.SourceResultType;

import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DataSourceBuildInputsInternalTest {

    private static final LDContext CONTEXT = LDContext.create("test-user");
    private static final File CACHE_DIR = new File(System.getProperty("java.io.tmpdir"));

    private static DataSourceBuildInputsInternal makeInternalInputs(
            PersistentDataStoreWrapper.ReadOnlyPerEnvironmentData envData
    ) {
        return new DataSourceBuildInputsInternal(
                CONTEXT, null, null, false,
                () -> Selector.EMPTY, null, CACHE_DIR,
                LDLogger.none(), envData
        );
    }

    private static DataSourceBuildInputs makePlainInputs() {
        return new DataSourceBuildInputs(
                CONTEXT, null, null, false,
                () -> Selector.EMPTY, null, CACHE_DIR,
                LDLogger.none()
        );
    }

    // ---- get() unwrap behavior ----

    @Test
    public void get_withInternalInstance_returnsSameInstance() {
        DataSourceBuildInputsInternal internal = makeInternalInputs(null);
        assertSame(internal, DataSourceBuildInputsInternal.get(internal));
    }

    @Test
    public void get_withPlainInputs_wrapsWithNullInternalFields() {
        DataSourceBuildInputs plain = makePlainInputs();
        DataSourceBuildInputsInternal result = DataSourceBuildInputsInternal.get(plain);

        assertNotNull(result);
        assertNull(result.getPerEnvironmentDataIfAvailable());
    }

    @Test
    public void get_withPlainInputs_preservesBaseProperties() {
        DataSourceBuildInputs plain = makePlainInputs();
        DataSourceBuildInputsInternal result = DataSourceBuildInputsInternal.get(plain);

        assertEquals(plain.getEvaluationContext(), result.getEvaluationContext());
        assertEquals(plain.getServiceEndpoints(), result.getServiceEndpoints());
        assertEquals(plain.getHttp(), result.getHttp());
        assertEquals(plain.isEvaluationReasons(), result.isEvaluationReasons());
        assertEquals(plain.getSelectorSource(), result.getSelectorSource());
        assertEquals(plain.getSharedExecutor(), result.getSharedExecutor());
        assertEquals(plain.getCacheDir(), result.getCacheDir());
        assertEquals(plain.getBaseLogger(), result.getBaseLogger());
    }

    // ---- getPerEnvironmentDataIfAvailable() ----

    @Test
    public void getPerEnvironmentDataIfAvailable_returnsProvidedValue() {
        PersistentDataStoreWrapper.ReadOnlyPerEnvironmentData envData = hashedContextId -> null;
        DataSourceBuildInputsInternal internal = makeInternalInputs(envData);

        assertSame(envData, internal.getPerEnvironmentDataIfAvailable());
    }

    @Test
    public void getPerEnvironmentDataIfAvailable_returnsNullWhenNotProvided() {
        DataSourceBuildInputsInternal internal = makeInternalInputs(null);

        assertNull(internal.getPerEnvironmentDataIfAvailable());
    }

    // ---- CacheInitializerBuilderImpl integration ----

    @Test
    public void cacheInitializerBuilder_withInternalInputs_receivesEnvData() throws Exception {
        String hashedContextId = LDUtil.urlSafeBase64HashedContextId(CONTEXT);
        Map<String, DataModel.Flag> flags = new HashMap<>();
        flags.put("flag1", new FlagBuilder("flag1").version(1).value(LDValue.of("yes")).build());

        PersistentDataStoreWrapper.ReadOnlyPerEnvironmentData envData =
                id -> hashedContextId.equals(id)
                        ? EnvironmentData.copyingFlagsMap(flags)
                        : null;

        DataSourceBuildInputsInternal inputs = makeInternalInputs(envData);
        Initializer initializer = new DataSystemComponents.CacheInitializerBuilderImpl().build(inputs);

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertEquals(ChangeSetType.Full, result.getChangeSet().getType());
        assertEquals(1, result.getChangeSet().getData().size());
        assertTrue(result.getChangeSet().getData().containsKey("flag1"));
    }

    @Test
    public void cacheInitializerBuilder_withPlainInputs_treatsAsNullEnvData() throws Exception {
        DataSourceBuildInputs plain = makePlainInputs();
        Initializer initializer = new DataSystemComponents.CacheInitializerBuilderImpl().build(plain);

        FDv2SourceResult result = initializer.run().get(1, TimeUnit.SECONDS);

        assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
        assertEquals(ChangeSetType.None, result.getChangeSet().getType());
    }
}
