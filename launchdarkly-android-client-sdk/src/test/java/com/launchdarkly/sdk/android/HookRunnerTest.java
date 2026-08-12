package com.launchdarkly.sdk.android;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.integrations.EvaluationExposureKey;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HookRunnerTest extends EasyMockSupport {
    // Every evaluation in these tests is the same exposure, so the runner's supplier returns this.
    private static final EvaluationExposureKey EXPOSURE_KEY =
            new EvaluationExposureKey("mobile-key-hash", "test-flag", 1, 2, "user-123");

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

    /**
     * Records the evaluation stages it observes, so a test can tell a suppressed evaluation (no
     * stages) from a reported one.
     */
    private static class RecordingHook extends Hook {
        final List<String> stages = new ArrayList<>();

        RecordingHook(String name) {
            super(name);
        }

        @Override
        public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
            stages.add("before");
            return seriesData;
        }

        @Override
        public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData,
                                                   EvaluationDetail<LDValue> evaluationDetail) {
            stages.add("after");
            return seriesData;
        }
    }

    private void evaluate(HookRunner runner) {
        runner.withEvaluation("testMethod", "test-flag", LDContext.create("user-123"), LDValue.of(false),
                () -> EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off()));
    }

    /**
     * Reads what an evaluation's result identifies, the way a deduping hook does, so a test can tell
     * when the runner resolved that and how often.
     */
    private static class KeyReadingHook extends Hook {
        final List<EvaluationExposureKey> keys = new ArrayList<>();

        KeyReadingHook(String name) {
            super(name);
        }

        @Override
        public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
            keys.add(seriesContext.getEvaluationExposureKey());
            return seriesData;
        }
    }

    @Test
    public void tellsAHookWhatTheEvaluationsResultIdentifies() {
        KeyReadingHook hook = new KeyReadingHook("key-reading");
        HookRunner runner = new HookRunner(logging.logger, List.of(hook),
                (seriesContext, flag) -> EXPOSURE_KEY);

        evaluate(runner);

        assertEquals(List.of(EXPOSURE_KEY), hook.keys);
    }

    @Test
    public void doesNotBuildTheExposureKeyUnlessAHookAsksForIt() {
        RecordingHook hook = new RecordingHook("reporting-everything");
        List<String> keyRequests = new ArrayList<>();
        HookRunner runner = new HookRunner(logging.logger, List.of(hook),
                (seriesContext, flag) -> {
                    keyRequests.add(seriesContext.flagKey);
                    return EXPOSURE_KEY;
                });

        evaluate(runner);

        assertEquals(List.of(), keyRequests);
        assertEquals(List.of("before", "after"), hook.stages);
    }

    @Test
    public void describesTheEvaluationsOwnReadOfTheFlagToEveryHookThatAsks() {
        KeyReadingHook first = new KeyReadingHook("first");
        KeyReadingHook second = new KeyReadingHook("second");
        List<Flag> described = new ArrayList<>();
        HookRunner runner = new HookRunner(logging.logger, List.of(first, second),
                (seriesContext, flag) -> {
                    described.add(flag);
                    return EXPOSURE_KEY;
                });
        Flag flag = new FlagBuilder("test-flag").version(2).build();

        runner.withEvaluation("testMethod", "test-flag", LDContext.create("user-123"), LDValue.of(false), flag,
                () -> EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off()));

        // Both hooks describe the read handed to the evaluation, rather than a later look at the store.
        assertEquals(2, described.size());
        assertSame(flag, described.get(0));
        assertSame(flag, described.get(1));
        assertEquals(List.of(EXPOSURE_KEY), first.keys);
        assertEquals(List.of(EXPOSURE_KEY), second.keys);
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
        // afterTrack runs in registration order (forward), unlike the reversed after-evaluation/identify stages.
        assertEquals(afterTrackOrder, List.of("a", "b", "c"));
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
    public void givesAnEvaluationTheHooksItBeganWith() {
        List<String> stages = new ArrayList<>();
        Map<String, Object> seriesData = Collections.unmodifiableMap(Collections.emptyMap());

        Hook addedDuring = mock(Hook.class);
        expect(addedDuring.beforeEvaluation(anyObject(), anyObject())).andStubAnswer(() -> { stages.add("added:before"); return seriesData; });
        expect(addedDuring.afterEvaluation(anyObject(), anyObject(), anyObject())).andStubAnswer(() -> { stages.add("added:after"); return seriesData; });
        expect(testHook.beforeEvaluation(anyObject(), anyObject())).andStubAnswer(() -> {
            stages.add("first:before");
            hookRunner.addHook(addedDuring);
            return seriesData;
        });
        expect(testHook.afterEvaluation(anyObject(), anyObject(), anyObject())).andStubAnswer(() -> { stages.add("first:after"); return seriesData; });
        replayAll();

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        HookRunner.EvaluationMethod evaluationMethod = () -> evaluationResult;
        LDContext context = LDContext.create("user-123");
        hookRunner.withEvaluation("testMethod", "test-flag", context, LDValue.of(false), evaluationMethod);
        hookRunner.withEvaluation("testMethod", "test-flag", context, LDValue.of(false), evaluationMethod);

        // A hook registered part way through an evaluation runs from the next one, rather than joining a series whose
        // earlier stages it was not in.
        assertEquals(List.of("first:before", "first:after",
                             "first:before", "added:before", "added:after", "first:after"), stages);
        logging.assertNothingLogged();
    }

    @Test
    public void givesAnIdentifyTheHooksItBeganWith() {
        LDContext context = LDContext.create("user-123");
        Integer timeout = 10;

        IdentifySeriesResult identifyResult = new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.COMPLETED);
        IdentifySeriesContext seriesContext = new IdentifySeriesContext(context, timeout);
        Hook addedDuring = mock(Hook.class);

        expect(testHook.beforeIdentify(seriesContext, Collections.emptyMap())).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        expect(testHook.afterIdentify(seriesContext, Collections.emptyMap(), identifyResult)).andReturn(Collections.unmodifiableMap(Collections.emptyMap()));
        replayAll();

        // An identify's two stages are separated by a round trip, which is time enough for an application to register a
        // hook. The new hook is left for the next identify: there is no series data to give it for this one.
        HookRunner.AfterIdentifyMethod afterIdentifyMethod = hookRunner.identify(context, timeout);
        hookRunner.addHook(addedDuring);
        afterIdentifyMethod.invoke(identifyResult);

        verifyAll();
        logging.assertNothingLogged();
    }

    @Test
    public void addsHooksFromSeveralThreadsWithoutLosingAny() throws InterruptedException {
        int threads = 4;
        int hooksPerThread = 50;
        HookRunner runner = new HookRunner(logging.logger, Collections.emptyList());
        AtomicInteger evaluationsObserved = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    startLine.await();
                    for (int j = 0; j < hooksPerThread; j++) {
                        runner.addHook(new Hook("counting-hook") {
                            @Override
                            public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
                                evaluationsObserved.incrementAndGet();
                                return seriesData;
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }).start();
        }
        startLine.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS));

        EvaluationDetail<LDValue> evaluationResult = EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off());
        runner.withEvaluation("testMethod", "test-flag", LDContext.create("user-123"), LDValue.of(false), () -> evaluationResult);

        // Registrations racing one another are each kept, rather than one thread's copy of the list overwriting another's.
        assertEquals(threads * hooksPerThread, evaluationsObserved.get());
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
