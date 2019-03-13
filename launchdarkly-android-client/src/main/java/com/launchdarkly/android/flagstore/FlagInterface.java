package com.launchdarkly.android.flagstore;

import android.support.annotation.NonNull;

import com.google.gson.JsonElement;
import com.launchdarkly.android.EvaluationReason;

/**
 * Public interface for a Flag, to be used if exposing Flag model to public API methods.
 */
public interface FlagInterface {

    /**
     * Getter for flag's key
     *
     * @return The flag's key
     */
    @NonNull
    String getKey();

    /**
     * Getter for flag's value. The value along with the variation are provided by LaunchDarkly by
     * evaluating full flag rules against the specific user.
     *
     * @return The flag's value
     */
    JsonElement getValue();

    /**
     * Getter for the flag's environment version field. This is an environment global version that
     * is updated whenever any flag is updated in an environment. This field is nullable, as
     * LaunchDarkly may provide only one of version and flagVersion.
     *
     * @return The environment version for this flag
     */
    Integer getVersion();

    /**
     * Getter for the flag's version. This is a flag specific version that is updated when the
     * specific flag has been updated. This field is nullable, as LaunchDarkly may provide only one
     * of version and flagVersion.
     *
     * @return The flag's version
     */
    Integer getFlagVersion();

    /**
     * Getter for flag's variation. The variation along with the value are provided by LaunchDarkly
     * by evaluating full flag rules against the specific user.
     *
     * @return The flag's variation
     */
    Integer getVariation();

    /**
     * Getter for the flag's evaluation reason. The evaluation reason is provided by the server to
     * describe the underlying conditions leading to the selection of the flag's variation and value
     * when evaluated against the particular user.
     *
     * @return The reason describing the flag's evaluation result
     */
    EvaluationReason getReason();
}
