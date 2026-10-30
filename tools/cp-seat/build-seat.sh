#!/bin/bash
# CENTINEL cp-seat build: the SDK's sdk.jar + the seat's seat-classes, from
# source, on this Linux aarch64 JVM host.
#
# The SDK's 137 main sources ride javac against the android-34 platform
# android.jar with a synthesized BuildConfig (VERSION_NAME pinned to the
# clone's .release-please-manifest.json; DEBUG false) — the
# ANDROID-CLIENT-DRIVE.md 2.1 recipe; no AGP/aapt2 (they have no
# linux-aarch64 builds and the SDK has no resources to process).
# The seat's classes compile into seat-classes (which seat-serve.sh's
# classpath puts ahead of sdk.jar, so the seat's seams always win).
#
# Usage: tools/cp-seat/build-seat.sh [extra javac source paths for the seat]
set -e
SDK_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
JDK=/home/termag/termag/tools/jdk-17.0.20.1+1
ANDROID_JAR=/home/termag/termag/tools/android-sdk/home/platforms/android-34/android.jar
DEPS="$(cat "$SDK_DIR/tools/cp-seat/classpath-deps.txt")"
SEAT_SRC="$SDK_DIR/tools/cp-seat/src"
SEAT_CLASSES="$SDK_DIR/seat-classes"

BUILD="$(mktemp -d /tmp/sdk-build.XXXXXX)"
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/classes"

# 1. synthesize BuildConfig from the version-of-record
VERSION="$(python3 -c "import json;print(json.load(open('$SDK_DIR/.release-please-manifest.json'))['.'])")"
mkdir -p "$BUILD/gen/com/launchdarkly/sdk/android"
cat > "$BUILD/gen/com/launchdarkly/sdk/android/BuildConfig.java" <<EOF
package com.launchdarkly.sdk.android;
public final class BuildConfig {
  public static final boolean DEBUG = Boolean.parseBoolean("false");
  public static final String LIBRARY_PACKAGE_NAME = "com.launchdarkly.sdk.android";
  public static final String VERSION_NAME = "$VERSION";
}
EOF

# 2. the SDK jar
AAR=/home/termag/termag/tools/aar-extracted
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -cp "$ANDROID_JAR:$DEPS:$AAR/core-classes.jar:$AAR/timber-classes.jar:$AAR/monitor-classes.jar" \
  -d "$BUILD/classes" \
  $(find "$SDK_DIR/launchdarkly-android-client-sdk/src/main/java" -name '*.java') \
  "$BUILD/gen/com/launchdarkly/sdk/android/BuildConfig.java"
"$JDK/bin/jar" cf "$SDK_DIR/sdk.jar" -C "$BUILD/classes" .

# 3. the seat classes (compiled against the fresh sdk.jar + the AAR extractions);
#    the seat's translation layer rides the SDK's own contract-test layer
#    (com.launchdarkly.sdktest) — it compiles beside the jar
NANO="$SDK_DIR/tools/cp-seat/nanohttpd-2.3.1.jar"
# the seat's translation layer rides the SDK's own contract-test layer
# (com.launchdarkly.sdktest) + shared-test-code's store helpers; MainActivity
# prices the module's generated R class only (no aapt2 on this cell) and
# serves no CP face, so it is the one seat-side source that stays out.
# The whole seat-side surfaces compile FRESH into a temp dir which then
# replaces seat-classes (the seat-serve.sh classpath carriers seat-classes
# ahead of sdk.jar, so stale classes could shadow the fresh jar bytes)
SDKTEST_SRC="$(find "$SDK_DIR/contract-tests/src/main/java" "$SDK_DIR/shared-test-code/src/main/java" -name '*.java' ! -path '*MainActivity*' ! -path '*MockComponents*')"
SEAT_SRC="$(find "$SEAT_SRC" -name '*.java'; echo "$SDK_DIR/tools/seat-test/SeatRs.java")"
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -cp "$SDK_DIR/sdk.jar:$DEPS:$NANO:$AAR/core-classes.jar:$AAR/timber-classes.jar:$AAR/monitor-classes.jar" \
  -d "$BUILD/seat-new" \
  $SDKTEST_SRC $SEAT_SRC
rm -rf "$SEAT_CLASSES.new"
cp -r "$BUILD/seat-new" "$SEAT_CLASSES.new"
cp -r "$SEAT_CLASSES/org" "$SEAT_CLASSES.new/org" 2>/dev/null || true
cp "$SEAT_CLASSES"/*.jar "$SEAT_CLASSES.new/" 2>/dev/null || true
rm -rf "$SEAT_CLASSES.old"
mv "$SEAT_CLASSES" "$SEAT_CLASSES.old" && mv "$SEAT_CLASSES.new" "$SEAT_CLASSES"
rm -rf "$SEAT_CLASSES.old"

echo "built: sdk.jar ($(stat -c%s "$SDK_DIR/sdk.jar") bytes) + seat-classes"
