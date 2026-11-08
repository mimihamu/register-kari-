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
# Independent published checksum: https://gradle.org/release-checksums/
expected="553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
echo "Fetching Gradle $version, checking against the official independent SHA-256"
sources=(
  "https://downloads.gradle.org/distributions/gradle-$version-bin.zip"
  "https://repo.huaweicloud.com/gradle/gradle-$version-bin.zip"
  "https://mirrors.aliyun.com/gradle/distributions/v$version/gradle-$version-bin.zip"
)
verified=false
for dist_url in "${sources[@]}"; do
  echo "Trying Gradle distribution: $dist_url"
  rm -f "$zip"
  if curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
       --connect-timeout 20 --max-time 600 "$dist_url" -o "$zip"; then
    if echo "$expected  $zip" | sha256sum --check --status; then
      verified=true
      break
    fi
    echo "::warning::Gradle mirror SHA-256 mismatch; refusing archive"
  fi
done
[[ "$verified" == true ]] || { echo "No verified Gradle 9.5.0 distribution available"; exit 1; }
unzip -tq "$zip" >/dev/null
unzip -q "$zip" -d "$dist_dir"
test -x "$dist_dir/gradle-$version/bin/gradle"
echo "$dist_dir/gradle-$version/bin" >> "${GITHUB_PATH:?}"
echo "Verified Gradle $version installed (official SHA-256)"
