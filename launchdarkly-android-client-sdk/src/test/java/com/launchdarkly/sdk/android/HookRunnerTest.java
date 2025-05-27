package com.launchdarkly.sdk.android;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.android.integrations.Hook;
import com.launchdarkly.sdk.android.integrations.HookMetadata;
import com.launchdarkly.sdk.android.integrations.IdentifySeriesContext;
import com.launchdarkly.sdk.android.integrations.IdentifySeriesResult;
import com.launchdarkly.sdk.android.integrations.TrackSeriesContext;

import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class HookRunnerTest extends EasyMockSupport {
    private HookRunner hookRunner;
    private Hook testHook;

    private static class TestHookMetaData extends HookMetadata {
        TestHookMetaData(String name) {
            super(name);
        }
    }

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    @Before
    public void setUp() {
        testHook = mock(Hook.class);
        hookRunner = new HookRunner(logging.logger, List.of(testHook));
    }

    @Test
    public void executesHooksAndReturnsEvaluationResult() {
        String method = "testMethod";
        String key = "test-flag";
        LDContext context = LDContext.create("user-123");
        LDValue defaultValue = LDValue.of(false);

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        EvaluationSeriesContext seriesContext = new EvaluationSeriesContext(method, key, context, defaultValue);
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;

        expect(testHook.beforeEvaluation(seriesContext, Collections.emptyMap())).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        expect(testHook.afterEvaluation(seriesContext, Collections.emptyMap(), evaluationResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        replayAll();

        EvaluationDetail<LDValue> result = hookRunner.withEvaluation(method, key, context, defaultValue, evaluationMethod);

        verifyAll();
        assertSame(evaluationResult, result);
        logging.assertNothingLogged();
    }

    @Test
    public void handlesErrorInEvaluationHooks() {
        String method = "testMethod";
        String key = "test-flag";
        LDContext context = LDContext.create("user-123");
        LDValue defaultValue = LDValue.of(false);
        RuntimeException exception = new RuntimeException("Hook error");

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        EvaluationSeriesContext seriesContext = new EvaluationSeriesContext(method, key, context, defaultValue);
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;

        expect(testHook.beforeEvaluation(seriesContext, Collections.emptyMap())).andThrow(exception);
        expect(testHook.getMetadata()).andReturn(new TestHookMetaData("TestHook"));
        expect(testHook.afterEvaluation(seriesContext, Collections.emptyMap(), evaluationResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        replayAll();

        EvaluationDetail<LDValue> result = hookRunner.withEvaluation(method, key, context, defaultValue, evaluationMethod);

        verifyAll();
        assertSame(evaluationResult, result);
        logging.assertErrorLogged(String.format("During evaluation of flag \"%s\". Stage \"beforeEvaluation\" of hook \"TestHook\" reported error: %s", key, exception));
    }

    @Test
    public void skipsEvaluationHookExecutionIfThereAreNoHooks() {
        HookRunner emptyHookRunner = new HookRunner(logging.logger, Collections.emptyList());

        String method = "testMethod";
        String key = "test-flag";
        LDContext context = LDContext.create("user-123");
        LDValue defaultValue = LDValue.of(false);

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;

        EvaluationDetail<LDValue> result = emptyHookRunner.withEvaluation(method, key, context, defaultValue, evaluationMethod);

        assertSame(evaluationResult, result);
        logging.assertNothingLogged();
    }

    @Test
    public void passesEvaluationSeriesDataFromBeforeToAfterHooks () {
        String method = "testMethod";
        String key = "test-flag";
        LDContext context = LDContext.create("user-123");
        LDValue defaultValue = LDValue.of(false);

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        EvaluationSeriesContext seriesContext = new EvaluationSeriesContext(method, key, context, defaultValue);
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;
        Map<String, Object> seriesData = Map.of("key-1", "value-1", "key-2", false);

        expect(testHook.beforeEvaluation(seriesContext, Collections.emptyMap())).andReturn(seriesData);
        expect(testHook.afterEvaluation(seriesContext, seriesData, evaluationResult)).andReturn(seriesData);
        replayAll();

        EvaluationDetail<LDValue> result = hookRunner.withEvaluation(method, key, context, defaultValue, evaluationMethod);

        verifyAll();
        assertSame(evaluationResult, result);
        logging.assertNothingLogged();
    }

    @Test
    public void executesEvaluationHookStagesInTheCorrectOrder() {
        List<String> beforeEvalOrder = new ArrayList<>();
        List<String> afterEvalOrder = new ArrayList<>();
        Map<String, Object> seriesData = Collections.unmodifiableMap(Collections.emptyMap());

        Hook hookA = mock(Hook.class);
        expect(hookA.beforeEvaluation(anyObject(), anyObject())).andStubAnswer(() -> { beforeEvalOrder.add("a"); return seriesData; });
        expect(hookA.afterEvaluation(anyObject(), anyObject(), anyObject())).andStubAnswer(() -> { afterEvalOrder.add("a"); return seriesData; });

        Hook hookB = mock(Hook.class);
        expect(hookB.beforeEvaluation(anyObject(), anyObject())).andStubAnswer(() -> { beforeEvalOrder.add("b"); return seriesData; });
        expect(hookB.afterEvaluation(anyObject(), anyObject(), anyObject())).andStubAnswer(() -> { afterEvalOrder.add("b"); return seriesData; });

        Hook hookC = mock(Hook.class);
        expect(hookC.beforeEvaluation(anyObject(), anyObject())).andStubAnswer(() -> { beforeEvalOrder.add("c"); return seriesData; });
        expect(hookC.afterEvaluation(anyObject(), anyObject(), anyObject())).andStubAnswer(() -> { afterEvalOrder.add("c"); return seriesData; });

        replayAll();

        HookRunner runner = new HookRunner(logging.logger, List.of(hookA, hookB, hookC));

        String method = "testMethod";
        String key = "test-flag";
        LDContext context = LDContext.create("user-123");
        LDValue defaultValue = LDValue.of(false);

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;

        runner.withEvaluation(method, key, context, defaultValue, evaluationMethod);

        verifyAll();
        assertEquals(beforeEvalOrder, List.of("a", "b", "c"));
        assertEquals(afterEvalOrder, List.of("c", "b", "a"));
    }

    @Test
    public void executesIdentifyHooks() {
        LDContext context = LDContext.create("user-123");
        Integer timeout = 10;

        IdentifySeriesResult identifyResult = new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.COMPLETED);
        IdentifySeriesContext seriesContext = new IdentifySeriesContext(context, timeout);

        expect(testHook.beforeIdentify(seriesContext, Collections.emptyMap())).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        expect(testHook.afterIdentify(seriesContext, Collections.emptyMap(), identifyResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        replayAll();

        HookRunner.AfterIdentifyMethod afterIdentifyMethod = hookRunner.identify(context, timeout);
        afterIdentifyMethod.invoke(identifyResult);

        verifyAll();
        logging.assertNothingLogged();
    }

    @Test
    public void handlesErrorInIdentifyHooks() {
        LDContext context = LDContext.create("user-123");
        Integer timeout = 10;
        RuntimeException exception = new RuntimeException("Hook error");

        IdentifySeriesResult identifyResult = new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.ERROR);
        IdentifySeriesContext seriesContext = new IdentifySeriesContext(context, timeout);

        expect(testHook.beforeIdentify(seriesContext, Collections.emptyMap())).andThrow(exception);
        expect(testHook.getMetadata()).andReturn(new TestHookMetaData("TestHook"));
        expect(testHook.afterIdentify(seriesContext, Collections.emptyMap(), identifyResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        replayAll();

        HookRunner.AfterIdentifyMethod afterIdentifyMethod = hookRunner.identify(context, timeout);
        afterIdentifyMethod.invoke(identifyResult);

        verifyAll();
        logging.assertErrorLogged(String.format("During identify with context \"%s\". Stage \"beforeIdentify\" of hook \"TestHook\" reported error: %s", context.getKey(), exception));
    }

    @Test
    public void passesIdentifySeriesDataFromBeforeToAfterHooks() {
        LDContext context = LDContext.create("user-123");
        Integer timeout = 10;

        IdentifySeriesResult identifyResult = new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.COMPLETED);
        IdentifySeriesContext seriesContext = new IdentifySeriesContext(context, timeout);
        Map<String, Object> seriesData = Map.of("key-1", "value-1", "key-2", false);

        expect(testHook.beforeIdentify(seriesContext, Collections.emptyMap())).andReturn(seriesData);
        expect(testHook.afterIdentify(seriesContext, seriesData, identifyResult)).andReturn(seriesData);
        replayAll();

        HookRunner.AfterIdentifyMethod afterIdentifyMethod = hookRunner.identify(context, timeout);
        afterIdentifyMethod.invoke(identifyResult);

        verifyAll();
        logging.assertNothingLogged();
    }

    @Test
    public void skipsIdentifyHookExecutionIfThereAreNoHooks() {
        HookRunner emptyHookRunner = new HookRunner(logging.logger, Collections.emptyList());

        LDContext context = LDContext.create("user-123");
        Integer timeout = 10;

        IdentifySeriesResult identifyResult = new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.COMPLETED);

        HookRunner.AfterIdentifyMethod afterIdentifyMethod = emptyHookRunner.identify(context, timeout);
        afterIdentifyMethod.invoke(identifyResult);

        logging.assertNothingLogged();
    }

    @Test
    public void executesIdentifyHookStagesInTheCorrectOrder() {
        List<String> beforeIdentifyOrder = new ArrayList<>();
        List<String> afterIdentifyOrder = new ArrayList<>();
        Map<String, Object> seriesData = Collections.unmodifiableMap(Collections.emptyMap());

        Hook hookA = mock(Hook.class);
        expect(hookA.beforeIdentify(anyObject(), anyObject())).andStubAnswer(() -> { beforeIdentifyOrder.add("a"); return seriesData; });
        expect(hookA.afterIdentify(anyObject(), anyObject(), anyObject())).andStubAnswer(() -> { afterIdentifyOrder.add("a"); return seriesData; });

        Hook hookB = mock(Hook.class);
        expect(hookB.beforeIdentify(anyObject(), anyObject())).andStubAnswer(() -> { beforeIdentifyOrder.add("b"); return seriesData; });
        expect(hookB.afterIdentify(anyObject(), anyObject(), anyObject())).andStubAnswer(() -> { afterIdentifyOrder.add("b"); return seriesData; });

        Hook hookC = mock(Hook.class);
        expect(hookC.beforeIdentify(anyObject(), anyObject())).andStubAnswer(() -> { beforeIdentifyOrder.add("c"); return seriesData; });
        expect(hookC.afterIdentify(anyObject(), anyObject(), anyObject())).andStubAnswer(() -> { afterIdentifyOrder.add("c"); return seriesData; });

        replayAll();

        HookRunner runner = new HookRunner(logging.logger, List.of(hookA, hookB, hookC));

        LDContext context = LDContext.create("user-123");
        Integer timeout = 10;

        IdentifySeriesResult identifyResult = new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.COMPLETED);

        HookRunner.AfterIdentifyMethod afterIdentifyMethod = runner.identify(context, timeout);
        afterIdentifyMethod.invoke(identifyResult);

        verifyAll();
        assertEquals(beforeIdentifyOrder, List.of("a", "b", "c"));
        assertEquals(afterIdentifyOrder, List.of("c", "b", "a"));
    }

    @Test
    public void executesAfterTrackHooks() {
        LDContext context = LDContext.create("user-123");
        String key = "test-event";
        LDValue data = LDValue.buildObject().put("test", "data").build();
        Double metricValue = 123.45;

        TrackSeriesContext seriesContext = new TrackSeriesContext(key, context, data, metricValue);

        testHook.afterTrack(seriesContext);
        expectLastCall().andVoid();
        replayAll();

        hookRunner.afterTrack(key, context, data, metricValue);

        verifyAll();
        logging.assertNothingLogged();
    }

    @Test
    public void handlesErrorInAfterTrackHooks() {
        LDContext context = LDContext.create("user-123");
        String key = "test-event";
        LDValue data = LDValue.buildObject().put("test", "data").build();
        Double metricValue = 123.45;
        RuntimeException exception = new RuntimeException("Hook error");

        TrackSeriesContext seriesContext = new TrackSeriesContext(key, context, data, metricValue);

        expect(testHook.getMetadata()).andReturn(new TestHookMetaData("TestHook"));
        testHook.afterTrack(seriesContext);
        expectLastCall().andThrow(exception);
        replayAll();

        hookRunner.afterTrack(key, context, data, metricValue);

        verifyAll();
        logging.assertErrorLogged(String.format("During tracking of event \"%s\". Stage \"afterTrack\" of hook \"TestHook\" reported error: %s", key, exception));
    }

    @Test
    public void skipsAfterTrackHookExecutionIfThereAreNoHooks() {
        HookRunner emptyHookRunner = new HookRunner(logging.logger, Collections.emptyList());

        LDContext context = LDContext.create("user-123");
        String key = "test-event";
        LDValue data = LDValue.buildObject().put("test", "data").build();
        Double metricValue = 123.45;

        emptyHookRunner.afterTrack(key, context, data, metricValue);

        logging.assertNothingLogged();
    }

    @Test
    public void executesAfterTrackHookStagesInTheCorrectOrder() {
        List<String> afterTrackOrder = new ArrayList<>();

        Hook hookA = mock(Hook.class);
        hookA.afterTrack(anyObject());
        expectLastCall().andStubAnswer(() -> { afterTrackOrder.add("a"); return null; });

        Hook hookB = mock(Hook.class);
        hookB.afterTrack(anyObject());
        expectLastCall().andStubAnswer(() -> { afterTrackOrder.add("b"); return null; });

        Hook hookC = mock(Hook.class);
        hookC.afterTrack(anyObject());
        expectLastCall().andStubAnswer(() -> { afterTrackOrder.add("c"); return null; });

        replayAll();

        HookRunner runner = new HookRunner(logging.logger, List.of(hookA, hookB, hookC));

        LDContext context = LDContext.create("user-123");
        String key = "test-event";
        LDValue data = LDValue.buildObject().put("test", "data").build();
        Double metricValue = 123.45;

        runner.afterTrack(key, context, data, metricValue);

        verifyAll();
        assertEquals(afterTrackOrder, List.of("c", "b", "a"));
    }

    @Test public void usesAddedHookInFutureInvocations() {
        Hook newHook = mock(Hook.class);
        hookRunner.addHook(newHook);

        String method = "testMethod";
        String key = "test-flag";
        LDContext context = LDContext.create("user-123");
        LDValue defaultValue = LDValue.of(false);

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        EvaluationSeriesContext seriesContext = new EvaluationSeriesContext(method, key, context, defaultValue);
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;

        expect(testHook.beforeEvaluation(seriesContext, Collections.emptyMap())).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        expect(newHook.beforeEvaluation(seriesContext, Collections.emptyMap())).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        expect(newHook.afterEvaluation(seriesContext, Collections.emptyMap(), evaluationResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        expect(testHook.afterEvaluation(seriesContext, Collections.emptyMap(), evaluationResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        replayAll();

        EvaluationDetail<LDValue> result = hookRunner.withEvaluation(method, key, context, defaultValue, evaluationMethod);

        verifyAll();
        assertSame(evaluationResult, result);
        logging.assertNothingLogged();
    }

    @Test
    public void logsUnknownHookWhenGetMetadataThrows() {
        String method = "testMethod";
        String key = "test-flag";
        LDContext context = LDContext.create("user-123");
        LDValue defaultValue = LDValue.of(false);
        RuntimeException exception = new RuntimeException("Hook error");

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        EvaluationSeriesContext seriesContext = new EvaluationSeriesContext(method, key, context, defaultValue);
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;

        expect(testHook.beforeEvaluation(seriesContext, Collections.emptyMap())).andThrow(exception);
        expect(testHook.getMetadata()).andThrow(new RuntimeException());
        expect(testHook.afterEvaluation(seriesContext, Collections.emptyMap(), evaluationResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        replayAll();

        EvaluationDetail<LDValue> result = hookRunner.withEvaluation(method, key, context, defaultValue, evaluationMethod);

        verifyAll();
        assertSame(evaluationResult, result);
        logging.assertErrorLogged("Exception thrown getting metadata for hook. Unable to get hook name.");
        logging.assertErrorLogged(String.format("During evaluation of flag \"%s\". Stage \"beforeEvaluation\" of hook \"unknown hook\" reported error: %s", key, exception));
    }

    @Test
    public void logsUnknownHookWhenGetMetadataReturnsNull() {
        String method = "testMethod";
        String key = "test-flag";
        LDContext context = LDContext.create("user-123");
        LDValue defaultValue = LDValue.of(false);
        RuntimeException exception = new RuntimeException("Hook error");

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        EvaluationSeriesContext seriesContext = new EvaluationSeriesContext(method, key, context, defaultValue);
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;

        expect(testHook.beforeEvaluation(seriesContext, Collections.emptyMap())).andThrow(exception);
        expect(testHook.getMetadata()).andReturn(null);
        expect(testHook.afterEvaluation(seriesContext, Collections.emptyMap(), evaluationResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        replayAll();

        EvaluationDetail<LDValue> result = hookRunner.withEvaluation(method, key, context, defaultValue, evaluationMethod);

        verifyAll();
        assertSame(evaluationResult, result);
        logging.assertErrorLogged("Exception thrown getting metadata for hook. Unable to get hook name.");
        logging.assertErrorLogged(String.format("During evaluation of flag \"%s\". Stage \"beforeEvaluation\" of hook \"unknown hook\" reported error: %s", key, exception));
    }

    @Test
    public void logsUnknownHookWhenGetMetadataReturnsEmptyName() {
        String method = "testMethod";
        String key = "test-flag";
        LDContext context = LDContext.create("user-123");
        LDValue defaultValue = LDValue.of(false);
        RuntimeException exception = new RuntimeException("Hook error");

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        EvaluationSeriesContext seriesContext = new EvaluationSeriesContext(method, key, context, defaultValue);
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;

        expect(testHook.beforeEvaluation(seriesContext, Collections.emptyMap())).andThrow(exception);
        expect(testHook.getMetadata()).andReturn(new TestHookMetaData(""));
        expect(testHook.afterEvaluation(seriesContext, Collections.emptyMap(), evaluationResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        replayAll();

        EvaluationDetail<LDValue> result = hookRunner.withEvaluation(method, key, context, defaultValue, evaluationMethod);

        verifyAll();
        assertSame(evaluationResult, result);
        logging.assertErrorLogged(String.format("During evaluation of flag \"%s\". Stage \"beforeEvaluation\" of hook \"unknown hook\" reported error: %s", key, exception));
    }

    @Test
    public void logsUnknownHookWhenGetMetadataReturnsNullName() {
        String method = "testMethod";
        String key = "test-flag";
        LDContext context = LDContext.create("user-123");
        LDValue defaultValue = LDValue.of(false);
        RuntimeException exception = new RuntimeException("Hook error");

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        EvaluationSeriesContext seriesContext = new EvaluationSeriesContext(method, key, context, defaultValue);
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;

        expect(testHook.beforeEvaluation(seriesContext, Collections.emptyMap())).andThrow(exception);
        expect(testHook.getMetadata()).andReturn(new TestHookMetaData(null));
        expect(testHook.afterEvaluation(seriesContext, Collections.emptyMap(), evaluationResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        replayAll();

        EvaluationDetail<LDValue> result = hookRunner.withEvaluation(method, key, context, defaultValue, evaluationMethod);

        verifyAll();
        assertSame(evaluationResult, result);
        logging.assertErrorLogged(String.format("During evaluation of flag \"%s\". Stage \"beforeEvaluation\" of hook \"unknown hook\" reported error: %s", key, exception));
    }
}
