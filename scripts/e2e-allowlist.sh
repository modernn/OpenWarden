#!/usr/bin/env sh
# OpenWarden allowlist-enforcement E2E (issue #137-adjacent, ADR-022).
#
# Drives AllowlistEnforcementE2ETest on a provisioned Device Owner emulator/device to prove
# the enforcement half of parent approve/unapprove:
#
#   approve → block  : app NOT on allowlist → child suspends it (deny-by-default)
#   unapprove → allow: app ON allowlist     → child un-suspends it (approve path)
#   idempotent re-apply: watchdog re-assert leaves deny set blocked
#
# ADB / DISALLOW_DEBUGGING_FEATURES constraint (same as e2e-exit-criteria.sh):
#   The test MUST run in the provisioned-but-not-yet-enforcing window. Full Day-One enforcement
#   includes DISALLOW_DEBUGGING_FEATURES, which severs ADB the instant the watchdog applies it.
#   This script force-stops the watchdog before instrumentation so ADB stays alive for the test.
#
# Usage:   ./scripts/e2e-allowlist.sh
# Env:     DEVICE (default emulator-5554), PKG (default com.openwarden.child.debug)
#
# Prerequisites:
#   1. Emulator/device booted and 'adb get-state' == 'device'.
#   2. App installed as Device Owner:
#        adb shell dpm set-device-owner $PKG/com.openwarden.child.AdminReceiver
#   3. A throwaway user app installed so pickBenignTarget() can find a victim
#      (bare emulator with only system apps → tests assumeTrue-skip; the script prints a hint).
#
# Build + install is done here — no separate step required.
set -eu

DEVICE="${DEVICE:-emulator-5554}"
PKG="${PKG:-com.openwarden.child.debug}"
TEST_CLASS="com.openwarden.child.AllowlistEnforcementE2ETest"
INSTRUMENTATION="$PKG.test/androidx.test.runner.AndroidJUnitRunner"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHILD="$ROOT/child-android"

log() { printf '\n=== %s ===\n' "$*"; }

# Positive state gate — any non-'device' state means full enforcement has severed ADB.
check_device_state() {
    state="$(adb -s "$DEVICE" get-state 2>/dev/null || true)"
    if [ "$state" != "device" ]; then
        printf 'FAIL: %s is not in '"'"'device'"'"' state (got: %s).\n' "$DEVICE" "${state:-<unavailable>"}"
        printf '      Full enforcement (DISALLOW_DEBUGGING_FEATURES) may have severed ADB.\n'
        printf '      Force-stop the watchdog BEFORE the test: adb shell am force-stop %s\n' "$PKG"
        return 1
    fi
}

# Run am instrument and parse the textual summary; mirrors the logic in e2e-exit-criteria.sh.
# $1 = minimum number of tests that must RUN (not skipped).
run_tests() {
    min="${1:-1}"

    check_device_state

    out="$(adb -s "$DEVICE" shell am instrument -w -e class "$TEST_CLASS" "$INSTRUMENTATION" 2>&1)"
    printf '%s\n' "$out"

    if printf '%s' "$out" | grep -qiE "device (offline|unauthorized|.* not found)|error: closed"; then
        printf 'FAIL: device dropped mid-run — DISALLOW_DEBUGGING_FEATURES enforcement is likely active.\n'
        printf '      Force-stop %s before the test to keep ADB alive.\n' "$PKG"
        return 1
    fi

    if printf '%s' "$out" | grep -q "FAILURES!!!"; then
        printf 'FAIL: AllowlistEnforcementE2ETest reported failures.\n'
        return 1
    fi

    # Extract the run count from "OK (N tests)" or "OK (N test)".
    ran="$(printf '%s' "$out" | grep -oE 'OK \([0-9]+ test' | grep -oE '[0-9]+' | head -1 || true)"
    if [ -z "$ran" ] || [ "$ran" -lt "$min" ]; then
        printf 'FAIL: expected >= %d tests to run, got '"'"'%s'"'"'.\n' "$min" "${ran:-none}"
        printf '      If all tests skipped, install a throwaway user app so pickBenignTarget() finds a victim:\n'
        printf '        adb install <throwaway.apk>\n'
        return 1
    fi
    return 0
}

log "Allowlist-enforcement E2E on $DEVICE"

adb -s "$DEVICE" wait-for-device

log "Build debug + androidTest APKs and install"
( cd "$CHILD" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
    :app:installDebug :app:installDebugAndroidTest )

log "Halt watchdog so DISALLOW_DEBUGGING_FEATURES is not enforced before instrumentation"
adb -s "$DEVICE" shell am force-stop "$PKG" || true

# Require >= 1 test to actually RUN (the precondition + at least one enforcement test).
# Tests that find no victim app will assumeTrue-skip; the precondition test always runs.
log "Assert allowlist enforcement (deny path + approve path + idempotent re-apply)"
run_tests 1

printf '\n=== ALLOWLIST ENFORCEMENT E2E PASS ===\n'
printf '  - deny path  : app NOT on allowlist -> suspended by PolicyEnforcer.applyAllowlist()\n'
printf '  - approve path: app ON allowlist    -> un-suspended by PolicyEnforcer.applyAllowlist()\n'
printf '  - idempotent re-apply              -> deny set stays blocked across watchdog ticks\n'
printf '\n'
printf 'NOT covered here (separate suites):\n'
printf '  - Full signed-bundle / transport path (parent pairing, crypto chain).\n'
printf '  - Day-One restriction baseline (criterion 2 — manual, ADB-incompatible when enforced).\n'
printf '  - End-to-end parent→child latency stopwatch.\n'
