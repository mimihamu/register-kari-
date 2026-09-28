#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME is required}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
AAPT2="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt2"
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/apksigner"
OUTPUT_DIR="${APK_INTEGRITY_OUTPUT_DIR:-artifacts/local}"
OUTPUT_FILE="$OUTPUT_DIR/apk-integrity-summary.txt"
EXPECTED_CERT_SHA256="752c4f56263c8887ada96184d25fad200aff0e84a80c67eda60c7607da3ac9e4"

fail(){ echo "LOCAL_APK_INTEGRITY_NG: $*" >&2; exit 1; }
normalize_sha(){ tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]'; }
attr(){
  local text="$1" key="$2"
  sed -n "s/.*$key='\([^']*\)'.*/\1/p" <<<"$text" | head -n 1
}
sdk_value(){
  local text="$1" key="$2"
  sed -n "s/^$key:'\([^']*\)'.*/\1/p" <<<"$text" | head -n 1
}

for tool in "$AAPT2" "$APKSIGNER"; do [[ -x "$tool" ]] || fail "missing tool: $tool"; done
command -v unzip >/dev/null || fail "unzip missing"
command -v sha256sum >/dev/null || fail "sha256sum missing"
mkdir -p "$OUTPUT_DIR"
: > "$OUTPUT_FILE"

verify_one(){
  local key="$1" apk="$2" pkg="$3" code="$4" name="$5"
  [[ -s "$apk" ]] || fail "$key APK missing: $apk"
  unzip -tq "$apk" >/dev/null || fail "$key ZIP integrity"

  local badging package_line actual_pkg actual_code actual_name min_sdk target_sdk launcher_count launcher
  badging="$("$AAPT2" dump badging "$apk")"
  package_line="$(grep '^package:' <<<"$badging" | head -n 1)"
  actual_pkg="$(attr "$package_line" name)"
  actual_code="$(attr "$package_line" versionCode)"
  actual_name="$(attr "$package_line" versionName)"
  min_sdk="$(sdk_value "$badging" sdkVersion)"
  target_sdk="$(sdk_value "$badging" targetSdkVersion)"
  launcher_count="$(grep -c '^launchable-activity:' <<<"$badging" || true)"
  launcher="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"

  [[ "$actual_pkg" == "$pkg" ]] || fail "$key package expected=$pkg actual=$actual_pkg"
  [[ "$actual_code" == "$code" ]] || fail "$key versionCode expected=$code actual=$actual_code"
  [[ "$actual_name" == "$name" ]] || fail "$key versionName expected=$name actual=$actual_name"
  [[ "$min_sdk" == "26" ]] || fail "$key minSdk expected=26 actual=$min_sdk"
  [[ "$target_sdk" == "36" ]] || fail "$key targetSdk expected=36 actual=$target_sdk"
  [[ "$launcher_count" == "1" && -n "$launcher" ]] || fail "$key launcher count=$launcher_count"

  local signature v2 cert
  signature="$("$APKSIGNER" verify --verbose --print-certs "$apk")"
  v2="$(sed -n 's/^Verified using v2 scheme (APK Signature Scheme v2): \(true\|false\)$/\1/p' <<<"$signature" | head -n 1)"
  [[ "$v2" == "true" ]] || fail "$key APK v2 signature"
  cert="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}' <<<"$signature" | normalize_sha)"
  [[ "$cert" == "$EXPECTED_CERT_SHA256" ]] || fail "$key cert expected=$EXPECTED_CERT_SHA256 actual=$cert"

  local sha
  sha="$(sha256sum "$apk" | awk '{print $1}')"
  {
    echo "${key}_APK_PATH=$apk"
    echo "${key}_PACKAGE=$actual_pkg"
    echo "${key}_VERSION_CODE=$actual_code"
    echo "${key}_VERSION_NAME=$actual_name"
    echo "${key}_MIN_SDK=$min_sdk"
    echo "${key}_TARGET_SDK=$target_sdk"
    echo "${key}_LAUNCHER=$launcher"
    echo "${key}_SIGNING_CERT_SHA256=$cert"
    echo "${key}_APK_SHA256=$sha"
    echo "${key}_ZIP_INTEGRITY=ok"
    echo "${key}_APK_SIGNATURE_V2=true"
  } >> "$OUTPUT_FILE"
}

verify_one REGISTER   "${POS_APK:-app/build/outputs/apk/debug/app-debug.apk}"   jp.co.tenposinfo.register.dev   "$POS_VERSION_CODE" "$POS_VERSION_NAME"

verify_one MANAGEMENT_APP   "${PLUS_APK:-management-app/build/outputs/apk/debug/management-app-debug.apk}"   jp.co.tenposinfo.register.plus.dev   "$PLUS_VERSION_CODE" "$PLUS_VERSION_NAME"

verify_one CUSTOMER_DISPLAY   "${CD_APK:-customer-display/build/outputs/apk/debug/customer-display-debug.apk}"   jp.co.tenposinfo.register.cd.dev   "$CD_VERSION_CODE" "$CD_VERSION_NAME"

echo "LOCAL_APK_RELEASE_INTEGRITY_GATE=passed" | tee -a "$OUTPUT_FILE"
