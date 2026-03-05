package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fallback and recovery conditions for switching between FDv2 synchronizers.
 * Conditions expose a future that completes when the condition fires.
 * Uses {@link LDAwaitFuture} so behavior is equivalent to CompletableFuture on all API levels.
 */
final class FDv2DataSourceConditions {

    static final long DEFAULT_FALLBACK_TIMEOUT_SECONDS = 2 * 60;
    static final long DEFAULT_RECOVERY_TIMEOUT_SECONDS = 5 * 60;

    enum ConditionType { FALLBACK, RECOVERY }

    /** Single condition: has a future that completes when it fires, inform(result), and close(). */
    interface Condition {
        LDAwaitFuture<ConditionType> getFuture();
        void inform(@NonNull FDv2SourceResult result);
        void close();
        ConditionType getType();
    }

    /**
     * Base for conditions that complete after a timeout. Holds the result future, executor,
     * timeout, and optional timer; subclasses define when the timer is started and what completes the future.
     */
    static abstract class TimedCondition implements Condition {
        protected final LDAwaitFuture<ConditionType> resultFuture = new LDAwaitFuture<>();
        protected final ScheduledExecutorService sharedExecutor;
        protected final long timeoutSeconds;
        /** Future for the timeout task, if any. Null when no timeout is active. */
        protected ScheduledFuture<?> timerFuture;

        TimedCondition(@NonNull ScheduledExecutorService sharedExecutor, long timeoutSeconds) {
            this.sharedExecutor = sharedExecutor;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public LDAwaitFuture<ConditionType> getFuture() {
            return resultFuture;
        }

        @Override
        public void close() {
            if (timerFuture != null) {
                timerFuture.cancel(false);
                timerFuture = null;
            }
        }
    }

    /**
     * Fallback: on INTERRUPTED start timer; on CHANGE_SET cancel timer. Future completes with FALLBACK when timer fires.
     */
    static final class FallbackCondition extends TimedCondition {

        FallbackCondition(@NonNull ScheduledExecutorService executor, long timeoutSeconds) {
            super(executor, timeoutSeconds);
        }

        @Override
        public void inform(@NonNull FDv2SourceResult result) {
            if (result.getResultType() == SourceResultType.CHANGE_SET) {
                if (timerFuture != null) {
                    timerFuture.cancel(false);
                    timerFuture = null;
                }
            }
            if (result.getResultType() == SourceResultType.STATUS
                    && result.getStatus() != null
                    && result.getStatus().getState() == SourceSignal.INTERRUPTED) {
                if (timerFuture == null) {
                    timerFuture = sharedExecutor.schedule(
                            () -> resultFuture.set(ConditionType.FALLBACK),
                            timeoutSeconds,
                            TimeUnit.SECONDS);
                }
            }
        }

        @Override
        public ConditionType getType() {
            return ConditionType.FALLBACK;
        }
    }

    /**
     * Recovery: timer starts when built. Future completes with RECOVERY when timer fires.
     */
    static final class RecoveryCondition extends TimedCondition {

        RecoveryCondition(@NonNull ScheduledExecutorService executor, long timeoutSeconds) {
            super(executor, timeoutSeconds);
            this.timerFuture = executor.schedule(
                    () -> resultFuture.set(ConditionType.RECOVERY),
                    timeoutSeconds,
                    TimeUnit.SECONDS);
        }

        @Override
        public void inform(@NonNull FDv2SourceResult result) {}

        @Override
        public ConditionType getType() {
            return ConditionType.RECOVERY;
        }
    }

    /**
     * Wraps a list of conditions; getFuture() completes when the first condition's future completes.
     */
    static final class Conditions implements AutoCloseable {
        private final List<Condition> conditions;
        private final LDAwaitFuture<Object> conditionsFuture;

        Conditions(@NonNull List<Condition> conditions) {
            this.conditions = new ArrayList<>(conditions);
            Future<?>[] futures = new Future<?>[conditions.size()];
            for (int i = 0; i < conditions.size(); i++) {
                futures[i] = conditions.get(i).getFuture();
            }
            this.conditionsFuture = LDFutures.anyOf(futures);
        }

        LDAwaitFuture<Object> getFuture() {
            return conditionsFuture;
        }

        void inform(@NonNull FDv2SourceResult result) {
            for (Condition c : conditions) {
                c.inform(result);
            }
        }

        @Override
        public void close() {
            for (Condition c : conditions) {
                c.close();
            }
        }
    }

    private FDv2DataSourceConditions() {}
}
