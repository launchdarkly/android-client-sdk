package com.launchdarkly.android;

import java.util.Arrays;
import java.util.Objects;
/**
 * An object returned by the "variation detail" methods such as {@link LDClientInterface#boolVariationDetail(String, boolean)},
 * combining the result of a flag evaluation with an explanation of how it was calculated.
 *
 * @since 2.7.0
 */
public class EvaluationDetail<T> {

    private final EvaluationReason reason;
    private final Integer variationIndex;
    private final T value;

    public EvaluationDetail(EvaluationReason reason, Integer variationIndex, T value) {
        this.reason = reason;
        this.variationIndex = variationIndex;
        this.value = value;
    }

    static <T> EvaluationDetail<T> error(EvaluationReason.ErrorKind errorKind, T defaultValue) {
        return new EvaluationDetail<>(EvaluationReason.error(errorKind), null, defaultValue);
    }

    /**
     * An object describing the main factor that influenced the flag evaluation value.
     *
     * @return an {@link EvaluationReason}
     */
    public EvaluationReason getReason() {
        return reason;
    }

    /**
     * The index of the returned value within the flag's list of variations, e.g. 0 for the first variation -
     * or {@code null} if the default value was returned.
     *
     * @return the variation index or null
     */
    public Integer getVariationIndex() {
        return variationIndex;
    }

    /**
     * The result of the flag evaluation. This will be either one of the flag's variations or the default
     * value that was passed to the {@code variation} method.
     *
     * @return the flag value
     */
    public T getValue() {
        return value;
    }

    /**
     * Returns true if the flag evaluation returned the default value, rather than one of the flag's
     * variations.
     *
     * @return true if this is the default value
     */
    public boolean isDefaultValue() {
        return variationIndex == null;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof EvaluationDetail) {
            @SuppressWarnings("unchecked")
            EvaluationDetail<T> o = (EvaluationDetail<T>) other;
            return Objects.equals(reason, o.reason) &&
                    Objects.equals(variationIndex, o.variationIndex) &&
                    Objects.equals(value, o.value);
        }
        return false;
    }

    @Override
    public int hashCode() {
        Object[] subObjects = {reason, variationIndex, value};
        return Arrays.hashCode(subObjects);
    }

    @Override
    public String toString() {
        return "{" + reason + "," + variationIndex + "," + value + "}";
    }
}
