package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * Fallback: on INTERRUPTED start timer; on CHANGE_SET cancel. Future completes with FALLBACK when timer fires.
     */
    static final class FallbackCondition implements Condition {
        private final LDAwaitFuture<ConditionType> resultFuture = new LDAwaitFuture<>();
        private final ScheduledExecutorService executor;
        private final long timeoutSeconds;
        private volatile ScheduledFuture<?> timerFuture;

        FallbackCondition(@NonNull ScheduledExecutorService executor, long timeoutSeconds) {
            this.executor = executor;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public LDAwaitFuture<ConditionType> getFuture() {
            return resultFuture;
        }

        @Override
        public void inform(@NonNull FDv2SourceResult result) {
            if (result.getResultType() == SourceResultType.CHANGE_SET) {
                cancel();
            } else if (result.getResultType() == SourceResultType.STATUS
                    && result.getStatus() != null
                    && result.getStatus().getState() == SourceSignal.INTERRUPTED) {
                if (timerFuture == null) {
                    timerFuture = executor.schedule(
                            () -> resultFuture.set(ConditionType.FALLBACK),
                            timeoutSeconds,
                            TimeUnit.SECONDS);
                }
            }
        }

        @Override
        public void close() {
            cancel();
        }

        @Override
        public ConditionType getType() {
            return ConditionType.FALLBACK;
        }

        private void cancel() {
            if (timerFuture != null) {
                timerFuture.cancel(false);
                timerFuture = null;
            }
        }
    }

    /**
     * Recovery: timer starts when built. Future completes with RECOVERY when timer fires.
     */
    static final class RecoveryCondition implements Condition {
        private final LDAwaitFuture<ConditionType> resultFuture = new LDAwaitFuture<>();
        private volatile ScheduledFuture<?> timerFuture;

        RecoveryCondition(@NonNull ScheduledExecutorService executor, long timeoutSeconds) {
            this.timerFuture = executor.schedule(
                    () -> resultFuture.set(ConditionType.RECOVERY),
                    timeoutSeconds,
                    TimeUnit.SECONDS);
        }

        @Override
        public LDAwaitFuture<ConditionType> getFuture() {
            return resultFuture;
        }

        @Override
        public void inform(@NonNull FDv2SourceResult result) {}

        @Override
        public void close() {
            if (timerFuture != null) {
                timerFuture.cancel(false);
                timerFuture = null;
            }
        }

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
            if (conditions.isEmpty()) {
                this.conditionsFuture = new LDAwaitFuture<>(); // never completes
            } else {
                this.conditionsFuture = new LDAwaitFuture<>();
                AtomicBoolean won = new AtomicBoolean(false);
                for (Condition c : conditions) {
                    c.getFuture().addListener(() -> {
                        if (won.compareAndSet(false, true)) {
                            try {
                                conditionsFuture.set((Object) c.getFuture().get());
                            } catch (Throwable t) {
                                Throwable cause = (t instanceof ExecutionException && t.getCause() != null) ? t.getCause() : t;
                                conditionsFuture.setException(cause);
                            }
                        }
                    });
                }
            }
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
