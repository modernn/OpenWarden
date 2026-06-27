#!/usr/bin/env bash
# OpenWarden local pre-push check — mirrors the CI gate (.github/workflows/ci.yml, ADR-044):
# ktlint (whole tree) + JVM unit tests for BOTH Gradle builds. E2E runs separately via
# scripts/e2e-exit-criteria.sh / test-emulator.sh.
#
# Needs JDK 17 and the `ktlint` binary on PATH (1.8.0, matching .editorconfig). The prior version of
# this script ran tasks that do not exist (`ktlintCheck`, `detekt`, `checkLicenses`,
# `:shared:specConformanceTest`) and the wrong module name (`:childAndroid` — the child module is
# `:app`), so it failed immediately; it now runs the same real tasks CI gates on.
set -euo pipefail

cd "$(dirname "$0")/.."

command -v ktlint >/dev/null 2>&1 || {
  echo "ktlint not found on PATH. Install ktlint 1.8.0 (the version CI pins, see ADR-044):" >&2
  echo "  https://github.com/pinterest/ktlint/releases/tag/1.8.0" >&2
  exit 1
}

echo "=== ktlint: child-android ==="
( cd child-android && ktlint )
echo "=== ktlint: parent-kmp ==="
( cd parent-kmp && ktlint )

echo "=== Unit tests: child-android ==="
( cd child-android && ./gradlew :app:testDebugUnitTest )

echo "=== Unit tests: parent-kmp ==="
( cd parent-kmp && ./gradlew :proto:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest )

echo ""
echo "All gate checks passed (matches CI)."
