#!/usr/bin/env bash
# Verify that current build produces hash matching last published release.
# Reads expected hash from .release-hashes.json.
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f .release-hashes.json ]]; then
  echo "No .release-hashes.json — skipping verification (first build)"
  exit 0
fi

EXPECTED=$(jq -r '."'"$(git describe --tags --abbrev=0 2>/dev/null || echo HEAD)"'"' .release-hashes.json 2>/dev/null || true)

if [[ -z "$EXPECTED" || "$EXPECTED" == "null" ]]; then
  echo "No expected hash for current tag — skipping"
  exit 0
fi

ACTUAL=$(sha256sum child-android/app/build/outputs/apk/release/app-release.apk | cut -d ' ' -f1)

if [[ "$EXPECTED" != "$ACTUAL" ]]; then
  echo "BUILD HASH MISMATCH"
  echo "Expected: $EXPECTED"
  echo "Actual:   $ACTUAL"
  exit 1
fi

echo "Build hash matches: $ACTUAL"
