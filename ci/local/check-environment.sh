#!/usr/bin/env bash
set -euo pipefail

BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"

fail() { echo "LOCAL_BUILD_ENV_NG: $*" >&2; exit 1; }
ok() { echo "LOCAL_BUILD_ENV_OK: $*"; }

command -v java >/dev/null 2>&1 || fail "java not found"
command -v git >/dev/null 2>&1 || fail "git not found"
command -v python3 >/dev/null 2>&1 || fail "python3 not found"
command -v unzip >/dev/null 2>&1 || fail "unzip not found"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum not found"

java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
[[ -n "$java_major" && "$java_major" -ge 17 ]] || fail "JDK 17+ required"
ok "JDK $java_major"

GRADLE_BIN=""
if [[ -x "./gradlew" ]]; then
    GRADLE_BIN="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="$(command -v gradle)"
else
    fail "Gradle not found (Gradle 9.5.0 expected)"
fi
ok "Gradle: $GRADLE_BIN"

: "${ANDROID_HOME:?ANDROID_HOME is required}"
[[ -f "$ANDROID_HOME/platforms/android-36/android.jar" ]] || fail "Android API 36 android.jar missing"
[[ -x "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt2" ]] || fail "aapt2 $BUILD_TOOLS_VERSION missing"
[[ -x "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/apksigner" ]] || fail "apksigner $BUILD_TOOLS_VERSION missing"
[[ -x "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/zipalign" ]] || fail "zipalign $BUILD_TOOLS_VERSION missing"

[[ -s "ci/tsuguregi-development.jks.b64" ]] || fail "fixed development signing key source missing"
[[ -f "ci/local/verify-apks.sh" ]] || fail "local APK integrity gate missing"

ok "Android API 36 / Build Tools $BUILD_TOOLS_VERSION"
ok "fixed signing source present"
echo "LOCAL_BUILD_ENV=ready"
