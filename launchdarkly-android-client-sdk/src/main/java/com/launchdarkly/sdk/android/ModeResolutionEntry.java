package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

/**
 * A single entry in a {@link ModeResolutionTable}. Pairs a condition with a
 * target {@link ConnectionMode}. If {@link Condition#test(ModeState)} returns
 * {@code true} for a given {@link ModeState}, this entry's {@code mode} is the
 * resolved result.
 * <p>
 * When user-configurable mode selection is added, {@code mode} can be replaced
 * with a resolver function to support indirection (e.g., returning a
 * user-configured foreground mode from {@code ModeState}).
 * <p>
 * Package-private — not part of the public SDK API.
 */
final class ModeResolutionEntry {

    /**
     * Functional interface for evaluating whether a {@link ModeResolutionEntry}
     * matches a given {@link ModeState}. Defined here to avoid a dependency on
     * {@code java.util.function.Predicate} (requires API 24+; SDK minimum is 21).
     */
    interface Condition {
        boolean test(@NonNull ModeState state);
    }

    private final Condition conditions;
    private final ConnectionMode mode;

    ModeResolutionEntry(
            @NonNull Condition conditions,
            @NonNull ConnectionMode mode
    ) {
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
