#!/usr/bin/env bash
# Release smoke tests: run the @SmokeTest-annotated androidTests against the
# MINIFIED release build on a connected device or emulator.
#
# Why this exists: every other test (unit + regular androidTest) runs debug
# artifacts, where R8 and the resource shrinker never execute. This suite is
# the only thing that catches release-only breakage (stripped reflective
# constructors, shrunk resources) before an APK reaches a phone.
#
# Usage: scripts/release-smoke-test.sh   (device/emulator must be connected)
set -euo pipefail
cd "$(dirname "$0")/.."

# No working Java? Use Android Studio's bundled JBR (the common macOS setup).
# `command -v java` is not enough: macOS ships a /usr/bin/java stub that exists
# but only prints "Unable to locate a Java Runtime".
if [[ -z "${JAVA_HOME:-}" ]] && ! java -version >/dev/null 2>&1; then
    jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    [[ -d "$jbr" ]] && export JAVA_HOME="$jbr"
fi

if ! adb get-state >/dev/null 2>&1; then
    echo "error: no device/emulator connected (adb get-state failed)" >&2
    echo "start one, e.g.: android emulator start Pixel_10" >&2
    exit 1
fi

# :smoke is a self-instrumenting com.android.test module (macrobenchmark
# architecture): it installs the real minified release APK and drives it from
# outside — widget host binding, launch intents — like the OS does.
# -PreleaseSmoke lets the release APK fall back to debug signing on machines
# without keystore.properties so it can be installed.
exec ./gradlew :smoke:connectedCheck -PreleaseSmoke
