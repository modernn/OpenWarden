#!/usr/bin/env bash
# Provision OpenWarden as Device Owner on a running emulator.
# Assumes emulator is booted + adb reachable.
set -euo pipefail

DEVICE="${DEVICE:-emulator-5554}"
APK="${APK:-child-android/app/build/outputs/apk/debug/app-debug.apk}"
DPC_COMPONENT="${DPC_COMPONENT:-com.openwarden.child/.AdminReceiver}"

echo "=== Check device ==="
adb -s "$DEVICE" wait-for-device

echo "=== Check factory-fresh ==="
PROVISIONED=$(adb -s "$DEVICE" shell settings get global device_provisioned | tr -d '\r\n')
if [[ "$PROVISIONED" == "1" ]]; then
  echo "WARN: device_provisioned=1; setting back to 0 (emulator only)"
  adb -s "$DEVICE" shell 'settings put global device_provisioned 0'
  adb -s "$DEVICE" shell 'settings put secure user_setup_complete 0'
fi

echo "=== Install OpenWarden APK ==="
adb -s "$DEVICE" install -r "$APK"

echo "=== Set Device Owner ==="
adb -s "$DEVICE" shell dpm set-device-owner "$DPC_COMPONENT"

echo "=== Verify ==="
adb -s "$DEVICE" shell dpm list-owners | grep -q "openwarden" && echo "DO set OK"

echo ""
echo "Provisioned. Open OpenWarden kid app on the device to verify."
