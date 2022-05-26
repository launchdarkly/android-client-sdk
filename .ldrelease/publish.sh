#!/bin/bash

# This override can be removed after merging https://github.com/launchdarkly/project-releaser/pull/133

set -ue

# Publish to Sonatype
echo "Publishing to Sonatype"
./gradlew publishToSonatype closeAndReleaseRepository || { echo "Gradle publish/release failed" >&2; exit 1; }
