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

fail() {
    echo "APK integrity gate NG: $*" >&2
    exit 1
}

for tool in "$AAPT2" "$APKSIGNER"; do
    test -x "$tool" || fail "required Android build tool not found: $tool"
done
command -v unzip >/dev/null 2>&1 || fail "unzip is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"

mkdir -p "$OUTPUT_DIR"
: > "$OUTPUT_FILE"

normalize_sha256() {
    tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]'
}

expect_equal() {
    local key="$1"
    local field="$2"
    local expected="$3"
    local actual="$4"
    if [[ "$actual" != "$expected" ]]; then
        fail "$key $field mismatch: expected='$expected' actual='${actual:-<empty>}'"
    fi
}

verify_apk() {
    local key="$1"
    local apk="$2"
    local expected_package="$3"
    local expected_code="$4"
    local expected_name="$5"
    local expected_min_sdk="$6"
    local expected_target_sdk="$7"

    test -s "$apk" || fail "$key APK is missing or empty: $apk"

    if ! unzip -tq "$apk" >/dev/null; then
        fail "$key APK ZIP integrity check failed: $apk"
    fi

    local badging
    if ! badging="$($AAPT2 dump badging "$apk" 2>&1)"; then
        echo "$badging" >&2
        fail "$key aapt2 dump badging failed"
    fi

    local package_line
    package_line="$(grep -m1 '^package:' <<<"$badging" || true)"
    local actual_package actual_code actual_name actual_min_sdk actual_target_sdk
    actual_package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$package_line")"
    actual_code="$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<<"$package_line")"
    actual_name="$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$package_line")"
    actual_min_sdk="$(sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
    actual_target_sdk="$(sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"

    echo "$key APK observed: package='${actual_package:-<empty>}' versionCode='${actual_code:-<empty>}' versionName='${actual_name:-<empty>}' minSdk='${actual_min_sdk:-<empty>}' targetSdk='${actual_target_sdk:-<empty>}'"

    expect_equal "$key" package "$expected_package" "$actual_package"
    expect_equal "$key" versionCode "$expected_code" "$actual_code"
    expect_equal "$key" versionName "$expected_name" "$actual_name"
    expect_equal "$key" minSdk "$expected_min_sdk" "$actual_min_sdk"
    expect_equal "$key" targetSdk "$expected_target_sdk" "$actual_target_sdk"

    local launcher_count launchable
    launcher_count="$(grep -c '^launchable-activity:' <<<"$badging" || true)"
    launchable="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
    echo "$key APK observed: launcherCount='$launcher_count' launcher='${launchable:-<empty>}'"
    expect_equal "$key" launcherCount "1" "$launcher_count"
    test -n "$launchable" || fail "$key launchable activity name is empty"

    local signature
    if ! signature="$($APKSIGNER verify --verbose --print-certs "$apk" 2>&1)"; then
        echo "$signature" >&2
        fail "$key apksigner verification failed"
    fi

    local signature_v2
    signature_v2="$(sed -n 's/^Verified using v2 scheme (APK Signature Scheme v2): \(true\|false\)$/\1/p' <<<"$signature" | head -n 1)"
    echo "$key APK observed: signatureV2='${signature_v2:-<empty>}'"
    expect_equal "$key" signatureV2 "true" "$signature_v2"

    local actual_cert
    actual_cert="$(
        awk -F': ' '/Signer #1 certificate SHA-256 digest:/ { print $2; exit }' <<<"$signature" \
            | normalize_sha256
    )"
    echo "$key APK observed: signingCertSha256='${actual_cert:-<empty>}'"
    test -n "$actual_cert" || fail "$key signing certificate SHA-256 was not reported by apksigner"
    expect_equal "$key" signingCertificateSha256 "$EXPECTED_CERT_SHA256" "$actual_cert"

    local apk_sha
    apk_sha="$(sha256sum "$apk" | awk '{print $1}')"

    {
        echo "${key}_APK_PATH=$apk"
        echo "${key}_PACKAGE=$actual_package"
        echo "${key}_VERSION_CODE=$actual_code"
        echo "${key}_VERSION_NAME=$actual_name"
        echo "${key}_MIN_SDK=$actual_min_sdk"
        echo "${key}_TARGET_SDK=$actual_target_sdk"
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
