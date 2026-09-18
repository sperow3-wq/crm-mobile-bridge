#!/usr/bin/env bash
set -euo pipefail
GRADLE_VERSION="${GRADLE_VERSION:-9.6.0}"

if ! command -v gradle >/dev/null 2>&1; then
  echo "Brak polecenia gradle. Uruchom ten skrypt w GitHub Actions albo po zainstalowaniu Gradle ${GRADLE_VERSION}." >&2
  exit 1
fi

gradle wrapper --gradle-version "${GRADLE_VERSION}" --distribution-type bin
chmod +x gradlew
./gradlew --version
