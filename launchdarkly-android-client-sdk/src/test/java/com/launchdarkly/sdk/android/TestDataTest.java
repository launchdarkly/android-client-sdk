package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.integrations.TestData;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.DataSource;

import org.junit.Test;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

public class TestDataTest {
    private static final LDContext initialContext = LDContext.create("user0");
    private static final EvaluationReason defaultReason = EvaluationReason.fallthrough();

    private final TestData td = TestData.dataSource();
    private final MockComponents.MockDataSourceUpdateSink updates = new MockComponents.MockDataSourceUpdateSink();

    @Test
    public void initializesWithEmptyData() {
        createAndStart();
        assertEquals(0, updates.expectInit().size());
    }

    @Test
    public void initializesWithFlags() {
        td.update(td.flag("flag1").variation(true))
                .update(td.flag("flag2").variation(false));

        createAndStart();

        Map<String, DataModel.Flag> initData = updates.expectInit();
        assertEquals(2, initData.size());

        DataModel.Flag flag1 = initData.get("flag1");
        assertEquals(new FlagBuilder("flag1").version(1).value(true).variation(0)
                .reason(defaultReason).build(), flag1);

        DataModel.Flag flag2 = initData.get("flag2");
        assertEquals(new FlagBuilder("flag2").version(1).value(false).variation(1)
                .reason(defaultReason).build(), flag2);
    }

    @Test
    public void addsFlag() {
        createAndStart();
        updates.expectInit();

        td.update(td.flag("flag1").variation(true));

        DataModel.Flag flag1 = updates.expectUpsert("flag1");
        assertEquals(new FlagBuilder("flag1").version(1).value(true).variation(0)
                .reason(defaultReason).build(), flag1);
    }

    @Test
    public void updatesFlag() {
        td.update(td.flag("flag1").variation(true));
        createAndStart();
        updates.expectInit();

        td.update(td.flag("flag1").variation(false));

        DataModel.Flag flag1 = updates.expectUpsert("flag1");
        assertEquals(new FlagBuilder("flag1").version(2).value(false).variation(1)
                .reason(defaultReason).build(), flag1);
    }

    @Test
    public void flagConfigBoolean() {
        Consumer<DataModel.Flag> expectTrue = flagValueAssertion(LDValue.of(true), 0);
        Consumer<DataModel.Flag> expectFalse = flagValueAssertion(LDValue.of(false), 1);

        verifyFlag(f -> f, expectTrue); // boolean true flag is the default
        verifyFlag(f -> f.booleanFlag(), expectTrue);
        verifyFlag(f -> f.variation(true), expectTrue);
        verifyFlag(f -> f.variation(false), expectFalse);

        verifyFlag(f -> f.variation(false).variationForUser(initialContext.getKey(), true), expectTrue);
        verifyFlag(f -> f.variation(true).variationForUser(initialContext.getKey(), false), expectFalse);

        verifyFlag(f -> f.variation(false).variationForKey(null, initialContext.getKey(), true), expectTrue);
        verifyFlag(f -> f.variation(false).variationForKey(ContextKind.DEFAULT, initialContext.getKey(), true), expectTrue);
        verifyFlag(f -> f.variation(true).variationForKey(null, initialContext.getKey(), false), expectFalse);
        verifyFlag(f -> f.variation(true).variationForKey(ContextKind.DEFAULT, initialContext.getKey(), false), expectFalse);
        verifyFlag(f -> f.variation(true).variationForKey(ContextKind.of("other"), initialContext.getKey(), true), expectFalse);

        verifyFlag(f -> f.variation(false).variationFunc(c -> c.getKey().equals(initialContext.getKey())), expectTrue);
        verifyFlag(f -> f.variation(true).variationFunc(c -> !c.getKey().equals(initialContext.getKey())), expectFalse);

        // variationForUser/variationForKey takes precedence over variationFunc
        verifyFlag(f -> f.variation(false).variationForUser(initialContext.getKey(), true)
                .variationFunc(c -> false), expectTrue);
    }

    @Test
    public void flagConfigByVariationIndex() {
        LDValue aVal = LDValue.of("a"), bVal = LDValue.of("b");
        int aIndex = 0, bIndex = 1;
        LDValue[] ab = new LDValue[] { aVal, bVal };
        Consumer<DataModel.Flag> expectA = flagValueAssertion(LDValue.of("a"), aIndex);
        Consumer<DataModel.Flag> expectB = flagValueAssertion(LDValue.of("b"), bIndex);

        verifyFlag(f -> f.variations(ab).variation(aIndex), expectA);
        verifyFlag(f -> f.variations(ab).variation(bIndex), expectB);

        verifyFlag(f -> f.variations(ab).variation(aIndex).variationForUser(initialContext.getKey(), bIndex), expectB);
        verifyFlag(f -> f.variations(ab).variation(bIndex).variationForUser(initialContext.getKey(), aIndex), expectA);

        verifyFlag(f -> f.variations(ab).variation(aIndex)
                .variationIndexFunc(c -> c.getKey().equals(initialContext.getKey()) ? bIndex : null), expectB);
        verifyFlag(f -> f.variations(ab).variation(bIndex)
                .variationIndexFunc(c -> c.getKey().equals(initialContext.getKey()) ? aIndex : null), expectA);

        // VariationForUser takes precedence over VariationFunc
        verifyFlag(f -> f.variations(ab).variation(aIndex).variationForUser(initialContext.getKey(), bIndex)
                .variationIndexFunc(c -> aIndex), expectB);
    }

    @Test
    public void flagConfigByVariationByValue() {
        LDValue aVal = LDValue.of("a"), bVal = LDValue.of("b");
        int aIndex = 0, bIndex = 1;
        LDValue[] ab = new LDValue[] { aVal, bVal };
        Consumer<DataModel.Flag> expectA = flagValueAssertion(LDValue.of("a"), aIndex);
        Consumer<DataModel.Flag> expectB = flagValueAssertion(LDValue.of("b"), bIndex);

        verifyFlag(f -> f.variations(ab).variation(aVal), expectA);
        verifyFlag(f -> f.variations(ab).variation(bVal), expectB);

        verifyFlag(f -> f.variations(ab).variation(aVal).variationForUser(initialContext.getKey(), bVal), expectB);
        verifyFlag(f -> f.variations(ab).variation(bVal).variationForUser(initialContext.getKey(), aVal), expectA);

        verifyFlag(f -> f.variations(ab).variation(aIndex)
                .variationIndexFunc(c -> c.getKey().equals(initialContext.getKey()) ? bIndex : null), expectB);
        verifyFlag(f -> f.variations(ab).variation(bIndex)
                .variationIndexFunc(c -> c.getKey().equals(initialContext.getKey()) ? aIndex : null), expectA);

        // VariationForUser takes precedence over VariationFunc
        verifyFlag(f -> f.variations(ab).variation(aIndex).variationForUser(initialContext.getKey(), bIndex)
                .variationIndexFunc(c -> aIndex), expectB);
    }

    private void createAndStart() {
        ClientContext clientContext = new ClientContext("", null, LDLogger.none(),
                new LDConfig.Builder(AutoEnvAttributes.Disabled).build(), updates, "", false,
                initialContext, null, false, null, null, false);
        DataSource ds = td.build(clientContext);
        AwaitableCallback<Boolean> callback = new AwaitableCallback<>();
        ds.start(callback);
        try {
            callback.await();
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private Consumer<DataModel.Flag> flagValueAssertion(LDValue value, int variationIndex) {
        return flag -> {
            assertEquals(value, flag.getValue());
            assertEquals(Integer.valueOf(variationIndex), flag.getVariation());
        };
    }

    private void verifyFlag(Function<TestData.FlagBuilder, TestData.FlagBuilder> builderActions,
                            Consumer<DataModel.Flag> flagAssertions) {

    }
}
