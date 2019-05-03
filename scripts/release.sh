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
sed -i.bak "s/version[ ]*=.*$/version = '${VERSION}'/" launchdarkly-android-client/build.gradle
rm -f launchdarkly-android-client/build.gradle.bak

# Update version in README.md:
sed -i.bak "s/com.launchdarkly:launchdarkly-android-client:[^']*/com.launchdarkly:launchdarkly-android-client:${VERSION}/" README.md
rm -f README.md.bak

./gradlew test sourcesJar javadocJar packageRelease
./gradlew uploadArchives closeAndReleaseRepository
./gradlew publishGhPages
echo "Finished android-client release."
