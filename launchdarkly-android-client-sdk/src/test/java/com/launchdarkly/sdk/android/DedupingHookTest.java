package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.DedupingHook;
import com.launchdarkly.sdk.android.integrations.EvaluationExposureDeduper;
import com.launchdarkly.sdk.android.integrations.EvaluationExposureKey;
import com.launchdarkly.sdk.android.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.android.integrations.Hook;
import com.launchdarkly.sdk.android.integrations.HookDecorator;
import com.launchdarkly.sdk.android.integrations.IdentifySeriesContext;
import com.launchdarkly.sdk.android.integrations.IdentifySeriesResult;
import com.launchdarkly.sdk.android.integrations.TrackSeriesContext;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives hooks through {@link HookRunner} rather than calling the decorator directly, because how the
 * runner carries series data from one stage to the next is what a decorator has to work with.
 */
public class DedupingHookTest {
    private static final EvaluationExposureKey EXPOSURE_KEY =
            new EvaluationExposureKey("default", "test-flag", 1, 2, false, "user-123");
    private static final EvaluationExposureKey OTHER_RESULT =
            new EvaluationExposureKey("default", "test-flag", 2, 2, false, "user-123");

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    /**
     * Records the stages it observes, so a test can tell a suppressed evaluation (no stages) from a
     * reported one.
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

        @Override
        public Map<String, Object> beforeIdentify(IdentifySeriesContext seriesContext, Map<String, Object> seriesData) {
            stages.add("beforeIdentify");
            return seriesData;
        }

        @Override
        public Map<String, Object> afterIdentify(IdentifySeriesContext seriesContext, Map<String, Object> seriesData,
                                                 IdentifySeriesResult result) {
            stages.add("afterIdentify");
            return seriesData;
        }

        @Override
        public void afterTrack(TrackSeriesContext seriesContext) {
            stages.add("afterTrack");
        }
    }

    /** A decorator with its own behavior, to check that decorators compose. */
    private static class CountingDecorator extends HookDecorator {
        int evaluationsForwarded = 0;
        int resultsForwarded = 0;

        CountingDecorator(Hook delegate) {
            super(delegate);
        }

        @Override
        public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
            evaluationsForwarded++;
            return super.beforeEvaluation(seriesContext, seriesData);
        }

        @Override
        public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData,
                                                   EvaluationDetail<LDValue> evaluationDetail) {
            resultsForwarded++;
            return super.afterEvaluation(seriesContext, seriesData, evaluationDetail);
        }
    }

    private HookRunner runner(EvaluationExposureKey key, Hook... hooks) {
        return new HookRunner(logging.logger, List.of(hooks), seriesContext -> key);
    }

    private void evaluate(HookRunner runner) {
        runner.withEvaluation("testMethod", "test-flag", LDContext.create("user-123"), LDValue.of(false),
                () -> EvaluationDetail.fromValue(LDValue.of(true), 1, EvaluationReason.off()));
    }

    private void identify(HookRunner runner) {
        runner.identify(LDContext.create("user-123"), null).invoke(new IdentifySeriesResult(IdentifySeriesResult.IdentifySeriesStatus.COMPLETED));
    }

    @Test
    public void skipsBothStagesOfARepeatedEvaluation() {
        RecordingHook hook = new RecordingHook("deduping");
        HookRunner runner = runner(EXPOSURE_KEY, new DedupingHook(hook, 60_000));

        evaluate(runner);
        evaluate(runner);

        // A suppressed evaluation must not leave a beforeEvaluation unmatched by its afterEvaluation.
        assertEquals(List.of("before", "after"), hook.stages);
    }

    @Test
    public void usesTheDefaultWindowWhenWrappedWithoutOne() {
        RecordingHook hook = new RecordingHook("deduping");
        HookRunner runner = runner(EXPOSURE_KEY, new DedupingHook(hook));

        evaluate(runner);
        evaluate(runner);

        assertEquals(List.of("before", "after"), hook.stages);
    }

    @Test
    public void anUnwrappedHookObservesEveryEvaluation() {
        RecordingHook hook = new RecordingHook("unwrapped");
        HookRunner runner = runner(EXPOSURE_KEY, hook);

        evaluate(runner);
        evaluate(runner);

        assertEquals(List.of("before", "after", "before", "after"), hook.stages);
    }

    @Test
    public void reportsAnEvaluationWhoseResultChanged() {
        RecordingHook hook = new RecordingHook("deduping");
        List<EvaluationExposureKey> keys = new ArrayList<>(List.of(EXPOSURE_KEY, EXPOSURE_KEY, OTHER_RESULT));
        HookRunner runner = new HookRunner(logging.logger, List.of(new DedupingHook(hook, 60_000)),
                seriesContext -> keys.remove(0));

        evaluate(runner);
        evaluate(runner);
        evaluate(runner);

        assertEquals(List.of("before", "after", "before", "after"), hook.stages);
    }

    @Test
    public void wrappedHooksAreDeduplicatedIndependentlyOfEachOther() {
        RecordingHook deduping = new RecordingHook("deduping");
        RecordingHook reportingEverything = new RecordingHook("reporting-everything");
        HookRunner runner = runner(EXPOSURE_KEY, new DedupingHook(deduping, 60_000), reportingEverything);

        evaluate(runner);
        evaluate(runner);

        assertEquals(List.of("before", "after"), deduping.stages);
        assertEquals(List.of("before", "after", "before", "after"), reportingEverything.stages);
    }

    @Test
    public void hooksWrappedSeparatelyDoNotSuppressEachOther() {
        RecordingHook first = new RecordingHook("first");
        RecordingHook second = new RecordingHook("second");
        HookRunner runner = runner(EXPOSURE_KEY, new DedupingHook(first, 60_000), new DedupingHook(second, 60_000));

        evaluate(runner);
        evaluate(runner);

        // Sharing one deduper would have let the first hook's report suppress the second hook's.
        assertEquals(List.of("before", "after"), first.stages);
        assertEquals(List.of("before", "after"), second.stages);
    }

    @Test
    public void hooksSharingOneDeduperShareItsWindow() {
        EvaluationExposureDeduper shared = new EvaluationExposureDeduper(60_000);
        RecordingHook first = new RecordingHook("first");
        RecordingHook second = new RecordingHook("second");
        HookRunner runner = runner(EXPOSURE_KEY, new DedupingHook(first, shared), new DedupingHook(second, shared));

        evaluate(runner);

        // The first hook's report starts the window, which suppresses the second hook's.
        assertEquals(List.of("before", "after"), first.stages);
        assertEquals(List.of(), second.stages);
    }

    @Test
    public void identifyReportsTheSameEvaluationAgain() {
        RecordingHook hook = new RecordingHook("deduping");
        HookRunner runner = runner(EXPOSURE_KEY, new DedupingHook(hook, 60_000));

        evaluate(runner);
        identify(runner);
        evaluate(runner);

        assertEquals(List.of("before", "after", "beforeIdentify", "afterIdentify", "before", "after"),
                hook.stages);
    }

    @Test
    public void forwardsTheStagesItDoesNotDeduplicate() {
        RecordingHook hook = new RecordingHook("deduping");
        HookRunner runner = runner(EXPOSURE_KEY, new DedupingHook(hook, 60_000));

        identify(runner);
        runner.afterTrack("event-key", LDContext.create("user-123"), LDValue.ofNull(), null);

        assertEquals(List.of("beforeIdentify", "afterIdentify", "afterTrack"), hook.stages);
    }

    @Test
    public void forwardsAnEvaluationWhoseResultTheSdkDidNotDescribe() {
        RecordingHook hook = new RecordingHook("deduping");
        // A series context built by something other than the SDK has no result to recognize repeats
        // by, so nothing is suppressed.
        HookRunner runner = new HookRunner(logging.logger, List.of(new DedupingHook(hook, 60_000)),
                seriesContext -> null);

        evaluate(runner);
        evaluate(runner);

        assertEquals(List.of("before", "after", "before", "after"), hook.stages);
    }

    @Test
    public void deduperStacksInsideAnotherDecorator() {
        RecordingHook hook = new RecordingHook("wrapped-twice");
        CountingDecorator counting = new CountingDecorator(new DedupingHook(hook, 60_000));
        HookRunner runner = runner(EXPOSURE_KEY, counting);

        evaluate(runner);
        evaluate(runner);

        // The outer decorator sees both evaluations, and the deduper inside it passes on one.
        assertEquals(2, counting.evaluationsForwarded);
        assertEquals(List.of("before", "after"), hook.stages);
    }

    @Test
    public void deduperStacksAroundAnotherDecorator() {
        RecordingHook hook = new RecordingHook("wrapped-twice");
        CountingDecorator counting = new CountingDecorator(hook);
        HookRunner runner = runner(EXPOSURE_KEY, new DedupingHook(counting, 60_000));

        evaluate(runner);
        evaluate(runner);

        // The deduper is outermost this time, so the decorator inside it sees only what it forwards.
        assertEquals(1, counting.evaluationsForwarded);
        assertEquals(List.of("before", "after"), hook.stages);
    }

    @Test
    public void aDeduperDoesNotSwallowTheStagesOfADeduperInsideIt() {
        RecordingHook hook = new RecordingHook("deduped-twice");
        CountingDecorator counting = new CountingDecorator(new DedupingHook(hook, 60_000));
        // The outer deduper reports everything, so what the inner one suppresses has to travel back
        // out through the decorator between them, which each stage of still belongs to.
        HookRunner runner = runner(EXPOSURE_KEY, new DedupingHook(counting, 0));

        evaluate(runner);
        evaluate(runner);

        assertEquals(2, counting.evaluationsForwarded);
        assertEquals(2, counting.resultsForwarded);
        assertEquals(List.of("before", "after"), hook.stages);
    }

    @Test
    public void namesTheWrappedHookWhenItReportsAnError() {
        Hook throwing = new Hook("throwing-hook") {
            @Override
            public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
                throw new RuntimeException("Hook error");
            }
        };
        HookRunner runner = runner(EXPOSURE_KEY, new DedupingHook(throwing, 60_000));

        evaluate(runner);

        logging.assertErrorLogged("During evaluation of flag \"test-flag\". Stage \"beforeEvaluation\" "
                + "of hook \"throwing-hook\" reported error: java.lang.RuntimeException: Hook error");
    }
}
