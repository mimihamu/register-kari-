#!/usr/bin/env bash
set -euo pipefail
version=9.5.0
# Honor an already valid installation, even if the GitHub setup action timed out.
if command -v gradle >/dev/null 2>&1 && gradle --version 2>/dev/null | grep -Fq "Gradle 9.5"; then
  echo "Gradle 9.5 already available"
  exit 0
fi
dist_dir="${RUNNER_TEMP:?}/tsuguregi-gradle-dist"
mkdir -p "$dist_dir"
zip="$dist_dir/gradle-$version-bin.zip"
sha_url="https://downloads.gradle.org/distributions/gradle-$version-bin.zip.sha256"
dist_url="https://downloads.gradle.org/distributions/gradle-$version-bin.zip"
echo "Downloading official Gradle $version and checksum without GitHub release redirect"
curl --fail --location --silent --show-error --retry 5 --retry-all-errors \
  --connect-timeout 20 --max-time 300 "$sha_url" -o "$zip.sha256"
expected="$(tr -d '\r\n ' < "$zip.sha256")"
[[ "$expected" =~ ^[a-f0-9]{64}$ ]] || { echo "Bad Gradle checksum"; exit 1; }
curl --fail --location --silent --show-error --retry 5 --retry-all-errors \
  --connect-timeout 20 --max-time 600 "$dist_url" -o "$zip"
echo "$expected  $zip" | sha256sum --check --status
unzip -tq "$zip" >/dev/null
unzip -q "$zip" -d "$dist_dir"
test -x "$dist_dir/gradle-$version/bin/gradle"
echo "$dist_dir/gradle-$version/bin" >> "${GITHUB_PATH:?}"
echo "Verified Gradle $version installed (official SHA-256)"
