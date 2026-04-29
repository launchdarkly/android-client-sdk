package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of attempting to register all configured {@link Plugin}s during SDK initialization.
 *
 * <p>This is a closed type hierarchy with exactly two subtypes: {@link Success} and {@link Failure}.
 */
public abstract class RegistrationCompleteResult {

    private RegistrationCompleteResult() {
        // sealed: only the nested subtypes may extend this class.
    }

    /**
     * @return a {@link Success} result indicating that every plugin registered without error.
     */
    @NonNull
    public static RegistrationCompleteResult success() {
        return Success.INSTANCE;
    }

    /**
     * @param failures the per-plugin failures collected during registration; must be non-null.
     * @return a {@link Failure} result wrapping the supplied failures.
     */
    @NonNull
    public static RegistrationCompleteResult failure(@NonNull List<Failure.PluginFailure> failures) {
        return new Failure(failures);
    }

    /**
     * Indicates that all plugins registered successfully.
     */
    public static final class Success extends RegistrationCompleteResult {
        /** The single instance. */
        public static final Success INSTANCE = new Success();

        private Success() {}

        @Override public boolean equals(Object other) { return other instanceof Success; }
        @Override public int hashCode() { return Success.class.hashCode(); }
        @Override public String toString() { return "Success"; }
    }

    /**
     * Indicates that one or more plugins failed to register. The wrapped list contains
     * the failure details for each plugin that threw during registration.
     */
    public static final class Failure extends RegistrationCompleteResult {
        @NonNull private final List<PluginFailure> failures;

        public Failure(@NonNull List<PluginFailure> failures) {
            this.failures = Collections.unmodifiableList(failures);
        }

        @NonNull
        public List<PluginFailure> getFailures() {
            return failures;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Failure)) return false;
            return failures.equals(((Failure) other).failures);
        }

        @Override
        public int hashCode() {
            return failures.hashCode();
        }

        @Override
        public String toString() {
            return "Failure(failures=" + failures + ")";
        }

        /**
         * Details about a single plugin's registration failure.
         */
        public static final class PluginFailure {
            @NonNull private final String pluginName;
            @Nullable private final String message;
            @Nullable private final Throwable cause;

            public PluginFailure(@NonNull String pluginName, @Nullable String message, @Nullable Throwable cause) {
                this.pluginName = pluginName;
                this.message = message;
                this.cause = cause;
            }

            @NonNull
            public String getPluginName() {
                return pluginName;
            }

            @Nullable
            public String getMessage() {
                return message;
            }

            @Nullable
            public Throwable getCause() {
                return cause;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof PluginFailure)) return false;
                PluginFailure that = (PluginFailure) other;
                return pluginName.equals(that.pluginName)
                        && Objects.equals(message, that.message)
                        && Objects.equals(cause, that.cause);
            }

            @Override
            public int hashCode() {
                return Objects.hash(pluginName, message, cause);
            }

            @Override
            public String toString() {
                return "PluginFailure(pluginName=" + pluginName
                        + ", message=" + message
                        + ", cause=" + cause + ")";
            }
        }
    }
}
