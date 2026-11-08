#!/usr/bin/env bash
set -euo pipefail
version=2.3.21
group_path="org/jetbrains/kotlin/kotlin-compiler-embeddable/$version"
filename="kotlin-compiler-embeddable-$version.jar"
# Published in JetBrains/kotlin's gradle/verification-metadata.xml.
expected="d3e70fb011675e77ef00f1a68918d3cd91eed26058977d1e3ea2a37a293233af"
local_repo="${RUNNER_TEMP:?}/tsuguregi-verified-maven"
dest="$local_repo/$group_path"
mkdir -p "$dest"
tmp="$dest/$filename.download"
trap 'rm -f "$tmp"' EXIT

# This repository is only a bootstrap fallback. Never trust a mirror's bytes:
# the jar must match the checksum published by the compiler's own source project.
urls=(
  "https://maven.wso2.org/nexus/content/repositories/central/$group_path/$filename"
  "https://maven.aliyun.com/repository/public/$group_path/$filename"
  "https://repo.maven.apache.org/maven2/$group_path/$filename"
)
verified=false
for url in "${urls[@]}"; do
  echo "Trying verified Kotlin compiler mirror: ${url%%/$group_path/*}"
  if curl --fail --location --silent --show-error --retry 2 --retry-all-errors \
      --connect-timeout 15 --max-time 240 "$url" -o "$tmp"; then
    if printf '%s  %s\n' "$expected" "$tmp" | sha256sum --check --status; then
      mv "$tmp" "$dest/$filename"
      verified=true
      break
    fi
    echo "::warning::Kotlin compiler mirror SHA-256 mismatch; refusing artifact"
  fi
done
if [[ "$verified" != true ]]; then
  echo "::warning::Verified Kotlin mirror unavailable; Gradle will use normal Maven repositories"
  exit 0
fi

pom="$dest/kotlin-compiler-embeddable-$version.pom"
for url in \
  "https://repo.maven.apache.org/maven2/$group_path/kotlin-compiler-embeddable-$version.pom" \
  "https://maven.wso2.org/nexus/content/repositories/central/$group_path/kotlin-compiler-embeddable-$version.pom"; do
  if curl --fail --location --silent --show-error --retry 2 --retry-all-errors \
      --connect-timeout 15 --max-time 60 "$url" -o "$pom"; then
    break
  fi
done
if [[ ! -s "$pom" ]]; then
  echo "::warning::POM download unavailable; standard Maven resolution only"
  rm -f "$dest/$filename"
  exit 0
fi
grep -Fq "<artifactId>kotlin-compiler-embeddable</artifactId>" "$pom"
grep -Fq "<version>2.3.21</version>" "$pom"
echo "CI_KOTLIN_COMPILER_REPO=$local_repo" >> "${GITHUB_ENV:?}"
echo "Using SHA-256 verified Kotlin compiler $version from local filtered Maven repository"
