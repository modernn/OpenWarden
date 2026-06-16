#!/usr/bin/env bash
# Verify OpenWarden dev environment is healthy.
# Exit 0 = ready. Non-zero = action required.
set -euo pipefail

cd "$(dirname "$0")/.."

[[ -f .bootstrap.env ]] && source .bootstrap.env
JDK_VERSION="${JDK_VERSION:-21}"

PASS=0; FAIL=0
check() {
  local name="$1" cmd="$2"
  if eval "$cmd" >/dev/null 2>&1; then
    echo "  OK   $name"
    PASS=$((PASS+1))
  else
    echo "  FAIL $name"
    FAIL=$((FAIL+1))
  fi
}

echo "OpenWarden environment check:"
check "JDK $JDK_VERSION+" "java -version 2>&1 | head -1 | grep -qE '\"(${JDK_VERSION}|[2-9][1-9])\\.'"
check "adb"               "command -v adb"
check "Android SDK"       "[[ -n \"\${ANDROID_HOME:-}\" || -d \"\$HOME/Library/Android/sdk\" || -d \"\$HOME/Android/Sdk\" ]]"
check "git"               "command -v git"
check "gh CLI (optional)" "command -v gh"
check "jq (optional)"     "command -v jq"
check "ktlint (optional)" "command -v ktlint"

# Phase 1+ checks (will start passing after scaffolding)
if [[ -f gradlew ]]; then
  check "Gradle wrapper"    "./gradlew --version"
fi

echo ""
echo "Passed: $PASS  Failed: $FAIL"
[[ $FAIL -eq 0 ]] && exit 0 || exit 1
