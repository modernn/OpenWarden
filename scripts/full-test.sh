#!/usr/bin/env bash
# OpenWarden full test entry point
# Runs unit + integration + lint. E2E run separately via test-emulator.sh.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "=== Lint ==="
./gradlew ktlintCheck detekt

echo "=== Unit tests ==="
./gradlew :proto:test :shared:test :childAndroid:testDebugUnitTest

echo "=== Spec conformance (test vectors) ==="
./gradlew :shared:specConformanceTest

echo "=== License audit ==="
./gradlew checkLicenses

echo "=== Reproducible-build hash check ==="
./gradlew :childAndroid:assembleRelease
./scripts/verify-build-hash.sh

echo ""
echo "All checks passed."
