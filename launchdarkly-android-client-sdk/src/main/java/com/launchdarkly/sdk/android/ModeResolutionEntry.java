package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

/**
 * A single entry in a {@link ModeResolutionTable}. Pairs a {@link Condition}
 * predicate with the {@link ConnectionMode} that should be activated when the
 * condition matches the current {@link ModeState}.
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ModeResolutionTable
 * @see ModeState
 */
final class ModeResolutionEntry {

    /**
     * Functional interface for evaluating a {@link ModeState} against a condition.
     * Defined here (rather than using {@code java.util.function.Predicate}) because
     * {@code Predicate} requires API 24+ and the SDK targets minSdk 21.
     */
    interface Condition {
        boolean test(@NonNull ModeState state);
    }

    private final Condition conditions;
    private final ConnectionMode mode;

    ModeResolutionEntry(@NonNull Condition conditions, @NonNull ConnectionMode mode) {
        this.conditions = conditions;
        this.mode = mode;
    }

    @NonNull
    Condition getConditions() {
        return conditions;
    }

    @NonNull
    ConnectionMode getMode() {
        return mode;
    }
}
