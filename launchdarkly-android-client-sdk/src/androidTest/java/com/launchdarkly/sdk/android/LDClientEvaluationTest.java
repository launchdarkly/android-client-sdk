package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LDClientEvaluationTest {
    private static final String mobileKey = "test-mobile-key";
    private static final LDContext context = LDContext.create("userKey");
    private static final String flagKey = "flag-key";
    private static final EvaluationReason reason = EvaluationReason.off();

    private Application application;

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void boolVariationReturnsValue() throws Exception {
        EnvironmentData data = new DataSetBuilder().add(flagKey, LDValue.of(true), 0).build();
        try (LDClient client = makeClientWithData(data)) {
            assertTrue(client.boolVariation(flagKey, false));
        }
    }

    @Test
    public void boolVariationReturnsDefaultForUnknownFlag() throws Exception {
        try (LDClient client = makeClientWithData(DataSetBuilder.emptyData())) {
            assertFalse(client.boolVariation(flagKey, false));
            assertTrue(client.boolVariation(flagKey, true));
        }
    }

    @Test
    public void boolVariationDetailReturnsValue() throws Exception {
        EnvironmentData data = new DataSetBuilder().add(
                new FlagBuilder(flagKey).value(true).variation(1).reason(reason).build()
        ).build();
        try (LDClient client = makeClientWithData(data)) {
            EvaluationDetail<Boolean> detail = client.boolVariationDetail(flagKey, false);
            assertTrue(detail.getValue());
            assertEquals(1, detail.getVariationIndex());
            assertEquals(reason, detail.getReason());
        }
    }

    @Test
    public void intVariationReturnsValue() throws Exception {
        EnvironmentData data = new DataSetBuilder().add(flagKey, LDValue.of(2), 0).build();
        try (LDClient client = makeClientWithData(data)) {
            assertEquals(2, client.intVariation(flagKey, 0));
        }
    }

    @Test
    public void intVariationCoercesDoubleValue() throws Exception {
        EnvironmentData data = new DataSetBuilder().add(flagKey, LDValue.of(3.25d), 0).build();
        try (LDClient client = makeClientWithData(data)) {
            assertEquals(3, client.intVariation(flagKey, 0));
        }
    }

    @Test
    public void intVariationReturnsDefaultForUnknownFlag() throws Exception {
        try (LDClient client = makeClientWithData(DataSetBuilder.emptyData())) {
            assertEquals(-1, client.intVariation(flagKey, -1));
        }
    }

    @Test
    public void intVariationDetailReturnsValue() throws Exception {
        EnvironmentData data = new DataSetBuilder().add(
                new FlagBuilder(flagKey).value(LDValue.of(2)).variation(1).reason(reason).build()
        ).build();
        try (LDClient client = makeClientWithData(data)) {
            EvaluationDetail<Integer> detail = client.intVariationDetail(flagKey, 0);
            assertEquals(2, detail.getValue().intValue());
            assertEquals(1, detail.getVariationIndex());
            assertEquals(reason, detail.getReason());
        }
    }

    @Test
    public void doubleVariationReturnsValue() throws Exception {
        EnvironmentData data = new DataSetBuilder().add(flagKey, LDValue.of(2.5d), 0).build();
        try (LDClient client = makeClientWithData(data)) {
            assertEquals(2.5d, client.doubleVariation(flagKey, 0), 0);
        }
    }

    @Test
    public void doubleVariationCoercesIntValue() throws Exception {
        EnvironmentData data = new DataSetBuilder().add(flagKey, LDValue.of(2), 0).build();
        try (LDClient client = makeClientWithData(data)) {
            assertEquals(2.0d, client.doubleVariation(flagKey, 0), 0);
        }
    }

    @Test
    public void doubleVariationReturnsDefaultForUnknownFlag() throws Exception {
        try (LDClient client = makeClientWithData(DataSetBuilder.emptyData())) {
            assertEquals(0.5d, client.doubleVariation(flagKey, 0.5d), 0);
        }
    }

    @Test
    public void doubleVariationDetailReturnsValue() throws Exception {
        EnvironmentData data = new DataSetBuilder().add(
                new FlagBuilder(flagKey).value(LDValue.of(2.5d)).variation(1).reason(reason).build()
        ).build();
        try (LDClient client = makeClientWithData(data)) {
            EvaluationDetail<Double> detail = client.doubleVariationDetail(flagKey, 0);
            assertEquals(2.5d, detail.getValue().doubleValue(), 0);
            assertEquals(1, detail.getVariationIndex());
            assertEquals(reason, detail.getReason());
        }
    }

    @Test
    public void stringVariationReturnsValue() throws Exception {
        EnvironmentData data = new DataSetBuilder().add(flagKey, LDValue.of("x"), 0).build();
        try (LDClient client = makeClientWithData(data)) {
            assertEquals("x", client.stringVariation(flagKey, "sorry"));
        }
    }

    @Test
    public void stringVariationReturnsDefaultForUnknownFlag() throws Exception {
        try (LDClient client = makeClientWithData(DataSetBuilder.emptyData())) {
            assertEquals("sorry", client.stringVariation(flagKey, "sorry"));
        }
    }

    @Test
    public void stringVariationDetailReturnsValue() throws Exception {
        EnvironmentData data = new DataSetBuilder().add(
                new FlagBuilder(flagKey).value(LDValue.of("x")).variation(1).reason(reason).build()
        ).build();
        try (LDClient client = makeClientWithData(data)) {
            EvaluationDetail<String> detail = client.stringVariationDetail(flagKey, "sorry");
            assertEquals("x", detail.getValue());
            assertEquals(1, detail.getVariationIndex());
            assertEquals(reason, detail.getReason());
        }
    }

    private LDClient makeClientWithData(EnvironmentData data) {
        ComponentConfigurer<DataSource> dataSourceConfig = clientContext ->
                MockComponents.successfulDataSource(clientContext, data, ConnectionInformation.ConnectionMode.POLLING,
                        null, null);
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(mobileKey)
                .dataSource(dataSourceConfig)
                .events(Components.noEvents())
                .logAdapter(logging.logAdapter)
                .persistentDataStore(new InMemoryPersistentDataStore())
                .build();
        return LDClient.init(application, config, context, 5);
    }
}
