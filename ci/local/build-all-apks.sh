#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
OUTPUT_DIR="${OUTPUT_DIR:-artifacts/local}"
mkdir -p "$OUTPUT_DIR"

bash ci/local/check-environment.sh

if [[ -x "./gradlew" ]]; then
    GRADLE_BIN="./gradlew"
else
    GRADLE_BIN="$(command -v gradle)"
fi

read_gradle_scalar() {
    local file="$1"
    local key="$2"
    awk -v key="$key" '
        /^[[:space:]]*\/\// { next }
        $1 == key && $2 == "=" {
            value=$3
            gsub(/"/, "", value)
            print value
            exit
        }
    ' "$file"
}

POS_VERSION_CODE="$(read_gradle_scalar app/build.gradle.kts versionCode)"
POS_VERSION_NAME="$(read_gradle_scalar app/build.gradle.kts versionName)"
PLUS_VERSION_CODE="$(read_gradle_scalar management-app/build.gradle.kts versionCode)"
PLUS_VERSION_NAME="$(read_gradle_scalar management-app/build.gradle.kts versionName)"
CD_VERSION_CODE="$(read_gradle_scalar customer-display/build.gradle.kts versionCode)"
CD_VERSION_NAME="$(read_gradle_scalar customer-display/build.gradle.kts versionName)"

[[ -n "$POS_VERSION_CODE" && -n "$POS_VERSION_NAME" ]] || { echo "version read failed for app" >&2; exit 1; }
[[ -n "$PLUS_VERSION_CODE" && -n "$PLUS_VERSION_NAME" ]] || { echo "version read failed for management-app" >&2; exit 1; }
[[ -n "$CD_VERSION_CODE" && -n "$CD_VERSION_NAME" ]] || { echo "version read failed for customer-display" >&2; exit 1; }

echo "=== cumulative unit tests ==="
"$GRADLE_BIN" --offline --no-daemon clean     :app:testDebugUnitTest     :customer-display:testDebugUnitTest     :management-app:testDebugUnitTest

echo "=== Kotlin compile ==="
"$GRADLE_BIN" --offline --no-daemon     :app:compileDebugKotlin     :customer-display:compileDebugKotlin     :management-app:compileDebugKotlin

echo "=== 3 APK build ==="
"$GRADLE_BIN" --offline --no-daemon     :app:assembleDebug     :customer-display:assembleDebug     :management-app:assembleDebug

export POS_VERSION_CODE POS_VERSION_NAME PLUS_VERSION_CODE PLUS_VERSION_NAME CD_VERSION_CODE CD_VERSION_NAME BUILD_TOOLS_VERSION
export APK_INTEGRITY_OUTPUT_DIR="$OUTPUT_DIR"
bash ci/local/verify-apks.sh

HEAD_SHA="$(git rev-parse HEAD 2>/dev/null || echo local)"
HEAD_SHORT="$(printf '%s' "$HEAD_SHA" | cut -c1-8)"

POS_OUT="$OUTPUT_DIR/TSUGUREGI_${POS_VERSION_NAME}_${HEAD_SHORT}_debug.apk"
PLUS_OUT="$OUTPUT_DIR/TSUGUREGI_PLUS_${PLUS_VERSION_NAME}_${HEAD_SHORT}_debug.apk"
CD_OUT="$OUTPUT_DIR/TSUGUREGI_CD_${CD_VERSION_NAME}_${HEAD_SHORT}_debug.apk"

cp app/build/outputs/apk/debug/app-debug.apk "$POS_OUT"
cp management-app/build/outputs/apk/debug/management-app-debug.apk "$PLUS_OUT"
cp customer-display/build/outputs/apk/debug/customer-display-debug.apk "$CD_OUT"

sha256sum "$POS_OUT" | tee "$POS_OUT.sha256"
sha256sum "$PLUS_OUT" | tee "$PLUS_OUT.sha256"
sha256sum "$CD_OUT" | tee "$CD_OUT.sha256"

{
    echo "HEAD=$HEAD_SHA"
    echo "POS_VERSION_CODE=$POS_VERSION_CODE"
    echo "POS_VERSION_NAME=$POS_VERSION_NAME"
    echo "PLUS_VERSION_CODE=$PLUS_VERSION_CODE"
    echo "PLUS_VERSION_NAME=$PLUS_VERSION_NAME"
    echo "CD_VERSION_CODE=$CD_VERSION_CODE"
    echo "CD_VERSION_NAME=$CD_VERSION_NAME"
    echo "BUILD_TOOLS_VERSION=$BUILD_TOOLS_VERSION"
    echo "LOCAL_BUILD=passed"
} | tee "$OUTPUT_DIR/local-build-summary.txt"

echo "=== LOCAL BUILD COMPLETE ==="
echo "$POS_OUT"
echo "$PLUS_OUT"
echo "$CD_OUT"
