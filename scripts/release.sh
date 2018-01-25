#!/usr/bin/env bash
# This script updates the version for the android-client library and releases the artifact + javadoc
# It will only work if you have the proper credentials set up in ~/.gradle/gradle.properties

# It takes exactly one argument: the new version.
# It should be run from the root of this git repo like this:
#   ./scripts/release.sh 4.0.9

# When done you should commit and push the changes made.

set -uxe
echo "Starting android-client release."

VERSION=$1

# Update version in gradle.properties file:
sed -e "s/version[ ]*=.*$/version = '${VERSION}'/" -i '' launchdarkly-android-client/build.gradle

# Update version in README.md:
sed -e "s/com.launchdarkly:launchdarkly-android-client:[^']*/com.launchdarkly:launchdarkly-android-client:${VERSION}/" -i '' README.md

./gradlew test sourcesJar javadocJar packageRelease uploadArchives closeAndReleaseRepository
./gradlew publishGhPages
echo "Finished android-client release."
