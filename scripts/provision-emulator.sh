#!/usr/bin/env bash
# Provision OpenWarden as Device Owner on a running emulator.
# Assumes emulator is booted + adb reachable.
set -euo pipefail

DEVICE="${DEVICE:-emulator-5554}"
APK="${APK:-child-android/app/build/outputs/apk/debug/app-debug.apk}"
# Must match the INSTALLED package: the default APK is the DEBUG build, whose applicationId carries
# the `.debug` suffix (build.gradle.kts), so the Device-Owner component is
# `com.openwarden.child.debug/com.openwarden.child.AdminReceiver` (the AdminReceiver CLASS stays in
# the com.openwarden.child package regardless of the applicationId suffix). For a release APK,
# override: DPC_COMPONENT=com.openwarden.child/com.openwarden.child.AdminReceiver.
DPC_COMPONENT="${DPC_COMPONENT:-com.openwarden.child.debug/com.openwarden.child.AdminReceiver}"

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
# Idempotent: `dpm set-device-owner` THROWS if a device owner is already set, so a re-run against an
# already-provisioned device would hard-fail. Skip when our component already owns the device.
if adb -s "$DEVICE" shell dpm list-owners | grep -qF "$DPC_COMPONENT"; then
  echo "Device Owner already set to $DPC_COMPONENT — skipping set-device-owner (re-run safe)"
else
  adb -s "$DEVICE" shell dpm set-device-owner "$DPC_COMPONENT"
fi

echo "=== Verify ==="
adb -s "$DEVICE" shell dpm list-owners | grep -q "openwarden" && echo "DO set OK"

echo ""
echo "Provisioned. Open OpenWarden kid app on the device to verify."
