#!/usr/bin/env bash
# This script updates the version for the android-client-sdk library and releases the artifact + javadoc
# It will only work if you have the proper credentials set up in ~/.gradle/gradle.properties

# It takes exactly one argument: the new version.
# It should be run from the root of this git repo like this:
#   ./scripts/release.sh 4.0.9

# When done you should commit and push the changes made.

set -uxe
echo "Starting android-client-sdk release."

VERSION=$1

# Update version in gradle.properties file:
sed -i.bak "s/version[ ]*=.*$/version = '${VERSION}'/" launchdarkly-android-client-sdk/build.gradle
rm -f launchdarkly-android-client-sdk/build.gradle.bak

./gradlew test sourcesJar javadocJar packageRelease
./gradlew uploadArchives closeAndReleaseRepository
./gradlew publishGhPages
echo "Finished android-client-sdk release."
