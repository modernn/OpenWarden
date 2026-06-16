#!/usr/bin/env bash
# OpenWarden emulator-based E2E. Boots Android Pixel 7 emulator, provisions, asserts.
# Long-running: ~10-15 minutes.
set -euo pipefail

cd "$(dirname "$0")/.."

EMULATOR_NAME="${EMULATOR_NAME:-openwarden-pixel7-api35}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-35;google_apis;x86_64}"

echo "=== Ensure AVD exists ==="
avdmanager list avd | grep -q "$EMULATOR_NAME" || \
  echo "no" | avdmanager create avd -n "$EMULATOR_NAME" -k "$SYSTEM_IMAGE" -d pixel_7

echo "=== Boot emulator (headless) ==="
emulator -avd "$EMULATOR_NAME" \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -port 5554 &
EMULATOR_PID=$!
trap "kill $EMULATOR_PID 2>/dev/null || true" EXIT

adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'

echo "=== Build OpenWarden debug APK ==="
./gradlew :childAndroid:assembleDebug

echo "=== Provision OpenWarden ==="
./scripts/provision-emulator.sh

echo "=== Verify /health ==="
adb -s emulator-5554 forward tcp:8181 tcp:8181
HEALTH=$(curl -s http://127.0.0.1:8181/health)
echo "$HEALTH" | jq -e '.do_set == true and .restrictions_count >= 17 and .frp_bound == true' \
  || { echo "FAIL: /health assertions"; exit 1; }

echo "=== Run instrumented sync tests ==="
./gradlew :childAndroid:connectedDebugAndroidTest

echo ""
echo "E2E PASS"
