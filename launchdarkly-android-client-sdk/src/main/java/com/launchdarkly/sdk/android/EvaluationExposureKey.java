package com.launchdarkly.sdk.android;

/**
 * Builds the key identifying an evaluation result for exposure deduplication.
 */
abstract class EvaluationExposureKey {
    private EvaluationExposureKey() {}

    /**
     * The variation and version pair is the same identity LaunchDarkly uses to bucket evaluations in
     * summary events, so two evaluations sharing that pair report identical data. Experiment status
     * needs its own component because the version reported on events is the flag's own version, which
     * only moves when the flag itself changes: a prerequisite flipping can move an evaluation into or
     * out of an experiment while it lands on the same variation of the same flag version.
     * <p>
     * The environment leads the key because a hook configured on {@code LDConfig} is one instance
     * shared by the clients for every environment in {@code secondaryMobileKeys}, and so is its
     * deduper. Without this component, two environments resolving a flag to the same variation of the
     * same version would look like a repeat of each other, and only the environment evaluating first
     * would reach the hook.
     *
     * @param environmentName the name of the environment being evaluated against
     * @param flagKey the flag key
     * @param variation the variation index of the result
     * @param flagVersion the flag version reported on events
     * @param inExperiment whether the evaluation was part of an experiment rollout
     * @param fullyQualifiedContextKey the fully qualified key of the evaluation context
     * @return a stable key identifying the evaluation result
     */
    static String of(String environmentName, String flagKey, int variation, int flagVersion,
                     boolean inExperiment, String fullyQualifiedContextKey) {
        return environmentName + '\n' + flagKey + '\n' + variation + '\n' + flagVersion + '\n'
                + inExperiment + '\n' + fullyQualifiedContextKey;
    }
}
