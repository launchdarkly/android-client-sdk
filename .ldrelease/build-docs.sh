#!/bin/bash

# This is the same as https://github.com/launchdarkly/project-releaser/blob/master/project_template/gradle/build-docs.sh
# but accounts for the fact that the docs appear in a different subdirectory.

set -ue

echo "Building Javadoc"
./gradlew javadoc || { echo "Javadoc build failed" >&2; exit 1; }

cp -r "${LD_RELEASE_PROJECT_DIR}/launchdarkly-android-client-sdk/build/docs/javadoc"/* "${LD_RELEASE_DOCS_DIR}"
