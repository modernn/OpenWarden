#!/usr/bin/env bash
# OpenWarden exit-criteria E2E (issue #30) — automated arm of docs/E2E_EXIT_CRITERIA.md.
#
# Verifies the exit criteria that are checkable over ADB on a provisioned device:
#   1. Device Owner provisioned (the outcome of "provision a Pixel 7 from factory") + provision time.
#   3. Block/unblock enforcement latency under 5s (device-side leg).
#
# Criterion 2 (Day-One restrictions intact) is intentionally NOT automated here, and structurally
# CANNOT be: the baseline includes DISALLOW_DEBUGGING_FEATURES, so the instant the watchdog enforces
# it the device drops to ADB `offline` (verified live). An ADB instrumentation test can observe the
# baseline only while it is ABSENT, never while ENFORCED — so criterion 2 is a manual on-device
# check (see the runbook). For the same reason this script does NOT reboot or otherwise trigger full
# enforcement before the test, and it force-stops the watchdog so it can reach ADB: it verifies
# DO + latency in the provisioned-but-not-yet-enforcing window.
#
# Oracle = the ExitCriteriaE2ETest connectedAndroidTest, driven via `am instrument`.
#
# Usage:   ./scripts/e2e-exit-criteria.sh
# Env:     DEVICE (default emulator-5554), PKG (default com.openwarden.child.debug).
#
# `dpm set-device-owner` refuses if accounts exist or a DO is already set (provision-emulator.sh
# resets the provisioned flag and skips re-setting an existing owner). Use a fresh AVD
# (`emulator -avd <name> -no-snapshot -wipe-data`) for a true from-factory provision-time number;
# note that on a fresh AVD the act of setting the owner starts the watchdog, which may sever ADB
# (that is criterion 2 in action) — run criteria 1+3 on a device where the watchdog is halted.
set -euo pipefail

DEVICE="${DEVICE:-emulator-5554}"
export ANDROID_SERIAL="$DEVICE"   # pin gradle install + adb default to this device (test bed has 2)
PKG="${PKG:-com.openwarden.child.debug}"
ADMIN="${ADMIN:-$PKG/com.openwarden.child.AdminReceiver}"
TEST_CLASS="com.openwarden.child.ExitCriteriaE2ETest"
INSTRUMENTATION="$PKG.test/androidx.test.runner.AndroidJUnitRunner"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHILD="$ROOT/child-android"
APK="$CHILD/app/build/outputs/apk/debug/app-debug.apk"

log() { echo ""; echo "=== $* ==="; }

# Run the instrumentation. `am instrument` exits 0 even on test failure, so parse the textual
# summary: detect an offline device (enforcement severed ADB), reject "FAILURES!!!", then require
# "OK (<n> tests)" with n >= $1 — the number of tests that must actually RUN (not assumeTrue-skip),
# guarding a 0-test class filter, a crash with no summary, or an all-skipped device.
run_tests() {
  local min="${1:-1}" out ran
  # Positive state gate (robust to adb's exact wording / locale): any non-`device` state — offline,
  # unauthorized, closed — means full enforcement (DISALLOW_DEBUGGING_FEATURES) has severed ADB.
  # Report that precisely rather than letting it fall through to a generic "0 tests" message.
  if [ "$(adb -s "$DEVICE" get-state 2>/dev/null || true)" != "device" ]; then
    echo "FAIL: $DEVICE is not in 'device' state — full enforcement (DISALLOW_DEBUGGING_FEATURES) has"
    echo "      likely severed ADB. Run criteria 1+3 in the provisioned-but-not-yet-enforcing window;"
    echo "      criterion 2 (restrictions intact) is the manual on-device check (docs/E2E_EXIT_CRITERIA.md)."
    return 1
  fi
  out="$(adb -s "$DEVICE" shell am instrument -w -e class "$TEST_CLASS" "$INSTRUMENTATION" 2>&1)"
  echo "$out"
  if echo "$out" | grep -qiE "device (offline|unauthorized|.* not found)|error: closed"; then
    echo "FAIL: device dropped mid-run — full enforcement (DISALLOW_DEBUGGING_FEATURES) is likely active."
    echo "      Criteria 1+3 must run in the provisioned-but-not-yet-enforcing window; criterion 2"
    echo "      (restrictions intact) is the manual on-device check — see docs/E2E_EXIT_CRITERIA.md."
    return 1
  fi
  if echo "$out" | grep -q "FAILURES!!!"; then
    echo "FAIL: ExitCriteriaE2ETest reported failures"; return 1
  fi
  # `|| true`: under pipefail a no-match grep fails the pipeline, which would abort the assignment
  # (set -e) before the graceful empty-check below. Keep it empty-on-no-match so we report cleanly.
  ran="$(echo "$out" | grep -oE "OK \([0-9]+ test" | grep -oE "[0-9]+" | head -1 || true)"
  if [ -z "$ran" ] || [ "$ran" -lt "$min" ]; then
    echo "FAIL: expected >= $min tests to run, got '${ran:-none}' (crash, 0-test filter, or all skipped)"
    return 1
  fi
  return 0
}

log "Exit-criteria E2E (criteria 1 + 3) on $DEVICE (admin $ADMIN)"
adb -s "$DEVICE" wait-for-device

# --- Criterion 1: provision (build, install app + test APK, set Device Owner) + time it ---
log "Build debug app + androidTest APKs"
( cd "$CHILD" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:installDebugAndroidTest )

log "Provision as Device Owner (timing the provision step)"
PROV_START="$(date +%s)"
DEVICE="$DEVICE" APK="$APK" DPC_COMPONENT="$ADMIN" bash "$ROOT/scripts/provision-emulator.sh"
PROV_S=$(( $(date +%s) - PROV_START ))
echo "Provision wall-clock: ${PROV_S}s (exit criterion: < 1800s / 30 min)"
if [ "$PROV_S" -ge 1800 ]; then echo "FAIL: provision exceeded the 30-minute budget"; exit 1; fi

# --- Keep ADB alive for the test: halt any running watchdog so it does not enforce
# DISALLOW_DEBUGGING_FEATURES (which would sever ADB) before criteria 1 + 3 are read. The enforced
# state is verified out-of-band (criterion 2, manual) precisely because it is ADB-incompatible. ---
log "Halt the watchdog so it does not disable ADB before the test (criterion 2 is checked manually)"
adb -s "$DEVICE" shell am force-stop "$PKG" || true

# --- Criteria 1 (DO active) + 3 (block/unblock latency). Require >= 2 tests to RUN: criterion1 and
# the always-on device-side latency probe 3a (3b assumeTrue-skips when there is no benign victim). ---
log "Assert criteria 1 + 3 (Device Owner + block/unblock latency)"
run_tests 2

echo ""
echo "=== EXIT-CRITERIA E2E PASS (criterion 1 + criterion 3 device-side) ==="
echo "  - Device Owner provisioned in ${PROV_S}s (< 30 min)"
echo "  - Block/unblock latency < 5s — device-side DPM leg only. This is a FLOOR: the watchdog is"
echo "    force-stopped, so it excludes service scheduling; the true parent→child number can only"
echo "    be larger (measure it with the manual stopwatch)."
echo ""
echo "NOT covered by this automated arm — see docs/E2E_EXIT_CRITERIA.md:"
echo "  - Criterion 2 (Day-One restrictions intact): MANUAL — enforcing the baseline disables ADB"
echo "    (DISALLOW_DEBUGGING_FEATURES), so it is verified on-device, not over ADB."
echo "  - True parent→child block/unblock stopwatch (sub-5s end-to-end)."
echo "  - 7-day bench uptime."
