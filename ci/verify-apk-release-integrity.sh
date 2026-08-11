#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${POS_VERSION_CODE:?POS_VERSION_CODE is required}"
: "${POS_VERSION_NAME:?POS_VERSION_NAME is required}"

BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
AAPT2="${ANDROID_HOME}/build-tools/${BUILD_TOOLS_VERSION}/aapt2"
APKSIGNER="${ANDROID_HOME}/build-tools/${BUILD_TOOLS_VERSION}/apksigner"
OUTPUT_DIR="${APK_INTEGRITY_OUTPUT_DIR:-artifacts}"
OUTPUT_FILE="${OUTPUT_DIR}/apk-integrity-summary.txt"
EXPECTED_CERT_SHA256="752c4f56263c8887ada96184d25fad200aff0e84a80c67eda60c7607da3ac9e4"

POS_APK="${POS_APK:-app/build/outputs/apk/debug/app-debug.apk}"
PLUS_APK="${PLUS_APK:-management-app/build/outputs/apk/debug/management-app-debug.apk}"
CD_APK="${CD_APK:-customer-display/build/outputs/apk/debug/customer-display-debug.apk}"

for tool in "$AAPT2" "$APKSIGNER"; do
    test -x "$tool" || {
        echo "required Android build tool not found: $tool" >&2
        exit 1
    }
done
command -v unzip >/dev/null 2>&1 || {
    echo "unzip is required" >&2
    exit 1
}

mkdir -p "$OUTPUT_DIR"
: > "$OUTPUT_FILE"

normalize_sha256() {
    tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]'
}

verify_apk() {
    local key="$1"
    local apk="$2"
    local expected_package="$3"
    local expected_code="$4"
    local expected_name="$5"
    local expected_min_sdk="$6"
    local expected_target_sdk="$7"

    test -s "$apk" || {
        echo "$key APK is missing or empty: $apk" >&2
        exit 1
    }

    unzip -tq "$apk" >/dev/null

    local badging
    badging="$($AAPT2 dump badging "$apk")"
    grep -Fq "package: name='$expected_package'" <<<"$badging"
    grep -Fq "versionCode='$expected_code'" <<<"$badging"
    grep -Fq "versionName='$expected_name'" <<<"$badging"
    grep -Fq "sdkVersion:'$expected_min_sdk'" <<<"$badging"
    grep -Fq "targetSdkVersion:'$expected_target_sdk'" <<<"$badging"

    local launcher_count
    launcher_count="$(grep -c '^launchable-activity:' <<<"$badging" || true)"
    test "$launcher_count" -eq 1 || {
        echo "$key expected exactly one launchable activity, found $launcher_count" >&2
        exit 1
    }

    local signature
    signature="$($APKSIGNER verify --verbose --print-certs "$apk")"
    grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$signature"

    local actual_cert
    actual_cert="$(
        awk -F': ' '/Signer #1 certificate SHA-256 digest:/ { print $2; exit }' <<<"$signature" \
            | normalize_sha256
    )"
    test -n "$actual_cert"
    test "$actual_cert" = "$EXPECTED_CERT_SHA256" || {
        echo "$key signing certificate SHA-256 mismatch" >&2
        exit 1
    }

    local launchable
    launchable="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
    local apk_sha
    apk_sha="$(sha256sum "$apk" | awk '{print $1}')"

    {
        echo "${key}_APK_PATH=$apk"
        echo "${key}_PACKAGE=$expected_package"
        echo "${key}_VERSION_CODE=$expected_code"
        echo "${key}_VERSION_NAME=$expected_name"
        echo "${key}_MIN_SDK=$expected_min_sdk"
        echo "${key}_TARGET_SDK=$expected_target_sdk"
        echo "${key}_LAUNCHER=$launchable"
        echo "${key}_SIGNING_CERT_SHA256=$actual_cert"
        echo "${key}_APK_SHA256=$apk_sha"
        echo "${key}_ZIP_INTEGRITY=ok"
        echo "${key}_APK_SIGNATURE_V2=true"
    } >> "$OUTPUT_FILE"
}

verify_apk \
    REGISTER \
    "$POS_APK" \
    'jp.co.tenposinfo.register.dev' \
    "$POS_VERSION_CODE" \
    "$POS_VERSION_NAME" \
    '26' \
    '36'

verify_apk \
    MANAGEMENT_APP \
    "$PLUS_APK" \
    'jp.co.tenposinfo.register.plus.dev' \
    '14' \
    '0.14.0-dev.1' \
    '26' \
    '36'

verify_apk \
    CUSTOMER_DISPLAY \
    "$CD_APK" \
    'jp.co.tenposinfo.register.cd.dev' \
    '7' \
    '0.14.0-dev.1' \
    '26' \
    '36'

echo 'APK_RELEASE_INTEGRITY_GATE=passed' >> "$OUTPUT_FILE"
echo 'APK_RELEASE_INTEGRITY_GATE=passed'
