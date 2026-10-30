#!/bin/bash
# Build + run the Android SDK / Robolectric smoke harness on a Linux aarch64 JVM host.
# Layout (all tool-local, cleanup-friendly):
#   classes/        compiled test classes + stub org/conscrypt (OpenSSLProvider only) + real conscrypt classes unpacked
#   stub-src/       the OpenSSLProvider stub source
#   extracted-cons/ unpacked org/conscrypt classes from conscrypt-openjdk-uber (signatures dropped)
# CPU: aarch64UserService JDK17 (no conscrypt native exists for linux-aarch64 in any published uber jar).
set -e
SEAT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SEAT_DIR"
JAVA_HOME=/home/termag/termag/tools/jdk-17.0.20.1+1
UBB=/home/termag/.cache/coursier/v1/https/repo1.maven.org/maven2/org/conscrypt/conscrypt-openjdk-uber/2.5.2/conscrypt-openjdk-uber-2.5.2.jar
SDK_JAR=/home/termag/termag/projects/android-client-sdk/sdk.jar
AAR_DIR=/home/termag/termag/tools/aar-extracted

# 1. unpack real org/conscrypt classes (byte-true, UNSIGNED once out of the jar)
rm -rf extracted-cons && mkdir extracted-cons
unzip -o -q "$UBB" "org/conscrypt/*" -d extracted-cons
cp -r extracted-cons/org/conscrypt classes/org/

# 2. compile test classes against sdk.jar + deps (android-all supplies android.* at run time)
NOUBER=$(tr ':' '\n' < /tmp/unified-cp.txt | grep -v "conscrypt-openjdk-uber" | tr '\n' ':' | sed 's/:$//')
SEAT_JARS="$SDK_JAR:$AAR_DIR/core-classes.jar:$AAR_DIR/timber-classes.jar:$AAR_DIR/monitor-classes.jar"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "classes:$SEAT_JARS:$NOUBER" -d classes \
  Seat.java Probe.java SeatInit.java "$@" 2>&1 | grep -i error | head -10

# 3. stub OpenSSLProvider wins LAST (bytes over the extracted real one)
cat > extracted-cons/org/conscrypt/OpenSSLProvider.java <<'EOF'
package org.conscrypt;
import java.security.Provider;
/** Stub: Robolectric constructs this inside its sandbox; no conscrypt native exists for aarch64 hosts. */
public final class OpenSSLProvider extends Provider {
  public OpenSSLProvider() { super("Conscrypt", "1.1", "stub (no native)"); }
  public OpenSSLProvider(String provName) { super(provName, "1.1", "stub (no native)"); }
  public OpenSSLProvider(String provName, double version, String info) { super(provName, "1.1", info); }
  public OpenSSLProvider(String provName, String version, String info) { super(provName, version, info); }
}
EOF
"$JAVA_HOME/bin/javac" -cp classes -d classes extracted-cons/org/conscrypt/OpenSSLProvider.java 2>&1 | grep -i error | head -5

# 4. run: plain http loopback + LDClient polling init under Robolectric
python3 mockld.py & SRVPID=$!; sleep 2
"$JAVA_HOME/bin/java" -cp "classes:$SEAT_JARS:$NOUBER" Seat SeatInit 2>&1 | tail -n ${1:-30}
kill $SRVPID 2>/dev/null
