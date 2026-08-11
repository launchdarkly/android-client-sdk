package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.LDValue;

import java.util.Objects;

/**
 * Identifies the evaluation result a hook is about to be told about, so that an
 * {@link EvaluationExposureDeduper} can recognize a repeat of it.
 * <p>
 * Two evaluations are the same exposure when every component here matches. The value is included
 * directly rather than inferred from the variation and version: those are the identity LaunchDarkly
 * uses to bucket summary events, but neither by itself guarantees that the payload is unchanged.
 * Experiment status needs its own component because a prerequisite flipping can move an evaluation
 * into or out of an experiment while it lands on the same value, variation, and flag version. The
 * environment is a component because a hook configured on {@code LDConfig} is one instance shared by
 * the clients for every environment in {@code secondaryMobileKeys}, and so is its deduper.
 * <p>
 * Instances are immutable, and their hash code is computed once, the first time one is asked for.
 */
public final class EvaluationExposureKey {
    private final String environmentName;
    private final String flagKey;
    private final LDValue value;
    private final int variation;
    private final int flagVersion;
    private final boolean inExperiment;
    private final String fullyQualifiedContextKey;

    // Computed on demand, because the SDK's own deduper recognizes a repeat by the flag a key belongs
    // to and the result it describes, and so never hashes a whole key: only a deduper of your own
    // that holds keys in a map or a set does. Races are benign, as every thread computes the same
    // value from fields that cannot change.
    private int hashCode;

    /**
     * Creates a key with a JSON null flag value. Prefer the overload accepting {@code value} when
     * the evaluation's flag payload is available.
     *
     * @param environmentName the name of the environment the evaluation was made against
     * @param flagKey the flag key
     * @param variation the variation index of the result
     * @param flagVersion the flag version reported on events
     * @param inExperiment whether the evaluation was part of an experiment rollout
     * @param fullyQualifiedContextKey the fully qualified key of the evaluation context
     */
    public EvaluationExposureKey(String environmentName, String flagKey, int variation,
                                 int flagVersion, boolean inExperiment,
                                 String fullyQualifiedContextKey) {
        this(environmentName, flagKey, LDValue.ofNull(), variation, flagVersion, inExperiment,
                fullyQualifiedContextKey);
    }

    /**
     * @param environmentName the name of the environment the evaluation was made against
     * @param flagKey the flag key
     * @param value the value in the flag payload, or JSON null if the flag was not found
     * @param variation the variation index of the result
     * @param flagVersion the flag version reported on events
     * @param inExperiment whether the evaluation was part of an experiment rollout
     * @param fullyQualifiedContextKey the fully qualified key of the evaluation context
     */
    public EvaluationExposureKey(String environmentName, String flagKey, LDValue value, int variation,
                                 int flagVersion, boolean inExperiment,
                                 String fullyQualifiedContextKey) {
        this.environmentName = environmentName;
        this.flagKey = flagKey;
        this.value = value;
        this.variation = variation;
        this.flagVersion = flagVersion;
        this.inExperiment = inExperiment;
        this.fullyQualifiedContextKey = fullyQualifiedContextKey;
    }

    /**
     * @return the name of the environment the evaluation was made against
     */
    public String getEnvironmentName() {
        return environmentName;
    }

    /**
     * @return the flag key
     */
    public String getFlagKey() {
        return flagKey;
    }

    /**
     * @return the value in the flag payload, or JSON null if the flag was not found
     */
    public LDValue getValue() {
        return value;
    }

    /**
     * @return the variation index of the result
     */
    public int getVariation() {
        return variation;
    }

    /**
     * @return the flag version reported on events
     */
    public int getFlagVersion() {
        return flagVersion;
    }

    /**
     * @return whether the evaluation was part of an experiment rollout
     */
    public boolean isInExperiment() {
        return inExperiment;
    }

    /**
     * @return the fully qualified key of the evaluation context
     */
    public String getFullyQualifiedContextKey() {
        return fullyQualifiedContextKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EvaluationExposureKey)) {
            return false;
        }

        EvaluationExposureKey o = (EvaluationExposureKey) other;
        // The primitives reject most unequal keys without touching the strings.
        return variation == o.variation
                && flagVersion == o.flagVersion
                && inExperiment == o.inExperiment
                && Objects.equals(flagKey, o.flagKey)
                && Objects.equals(environmentName, o.environmentName)
                && Objects.equals(value, o.value)
                && Objects.equals(fullyQualifiedContextKey, o.fullyQualifiedContextKey);
    }

    @Override
    public int hashCode() {
        int hash = hashCode;
        if (hash == 0) {
            hash = Objects.hashCode(environmentName);
            hash = 31 * hash + Objects.hashCode(flagKey);
            hash = 31 * hash + Objects.hashCode(value);
            hash = 31 * hash + variation;
            hash = 31 * hash + flagVersion;
            hash = 31 * hash + (inExperiment ? 1 : 0);
            hash = 31 * hash + Objects.hashCode(fullyQualifiedContextKey);
            hashCode = hash;
        }
        return hash;
    }

    @Override
    public String toString() {
        return "EvaluationExposureKey(environmentName=" + environmentName
                + ", flagKey=" + flagKey
                + ", value=" + value
                + ", variation=" + variation
                + ", flagVersion=" + flagVersion
                + ", inExperiment=" + inExperiment
                + ", fullyQualifiedContextKey=" + fullyQualifiedContextKey + ")";
    }
}
