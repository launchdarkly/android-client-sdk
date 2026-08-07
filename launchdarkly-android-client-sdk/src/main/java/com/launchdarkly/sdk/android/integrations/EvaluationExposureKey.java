package com.launchdarkly.sdk.android.integrations;

import java.util.Objects;

/**
 * Identifies the evaluation result a hook is about to be told about, so that an
 * {@link EvaluationExposureDeduper} can recognize a repeat of it.
 * <p>
 * Two evaluations are the same exposure when every component here matches. The variation and version
 * pair is the same identity LaunchDarkly uses to bucket evaluations in summary events, so two
 * evaluations sharing that pair report identical data. Experiment status needs its own component
 * because the version reported on events is the flag's own version, which only moves when the flag
 * itself changes: a prerequisite flipping can move an evaluation into or out of an experiment while it
 * lands on the same variation of the same flag version. The environment is a component because a hook
 * configured on {@code LDConfig} is one instance shared by the clients for every environment in
 * {@code secondaryMobileKeys}, and so is its deduper.
 * <p>
 * Instances are immutable, and their hash code is computed once, when the key is built.
 */
public final class EvaluationExposureKey {
    private final String environmentName;
    private final String flagKey;
    private final int variation;
    private final int flagVersion;
    private final boolean inExperiment;
    private final String fullyQualifiedContextKey;
    private final int hashCode;

    /**
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
        this.environmentName = environmentName;
        this.flagKey = flagKey;
        this.variation = variation;
        this.flagVersion = flagVersion;
        this.inExperiment = inExperiment;
        this.fullyQualifiedContextKey = fullyQualifiedContextKey;

        int hash = Objects.hashCode(environmentName);
        hash = 31 * hash + Objects.hashCode(flagKey);
        hash = 31 * hash + variation;
        hash = 31 * hash + flagVersion;
        hash = 31 * hash + (inExperiment ? 1 : 0);
        this.hashCode = 31 * hash + Objects.hashCode(fullyQualifiedContextKey);
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
        // The cached hash codes and the primitives reject unequal keys without touching the strings.
        return hashCode == o.hashCode
                && variation == o.variation
                && flagVersion == o.flagVersion
                && inExperiment == o.inExperiment
                && Objects.equals(flagKey, o.flagKey)
                && Objects.equals(environmentName, o.environmentName)
                && Objects.equals(fullyQualifiedContextKey, o.fullyQualifiedContextKey);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "EvaluationExposureKey(environmentName=" + environmentName
                + ", flagKey=" + flagKey
                + ", variation=" + variation
                + ", flagVersion=" + flagVersion
                + ", inExperiment=" + inExperiment
                + ", fullyQualifiedContextKey=" + fullyQualifiedContextKey + ")";
    }
}
