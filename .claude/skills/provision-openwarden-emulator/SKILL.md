---
name: provision-openwarden-emulator
description: Provision OpenWarden as Device Owner on a running Android emulator. Use to manually set up emulator state for debug work. Standalone wrapper around scripts/provision-emulator.sh.
---

# /provision-openwarden-emulator

Provisions OpenWarden as Device Owner on a running emulator. Does NOT boot the emulator — assumes one is running and reachable via `adb`.

## What it does

1. Verify emulator running via `adb devices`
2. Verify factory-fresh state (`device_provisioned=0`)
3. Install OpenWarden child APK
4. Run `dpm set-device-owner`
5. Apply day-one restrictions
6. Optionally pair with bench parent (if `--pair` flag)
7. Report `/health` endpoint state

## When to use

- Manual debug session — want emulator in known-provisioned state
- Reproducing a specific bug
- Trying out a new feature interactively

## When NOT to use

- Full E2E test — use `/test-openwarden-e2e-emulator`
- Real device — use `scripts/provision-bench-pixel.sh` instead

## Prerequisites

- Android emulator running (Pixel 7 API 35 image)
- ADB reachable (`adb devices` shows device)
- Built APK at `child-android/app/build/outputs/apk/debug/app-debug.apk`

## Output format

```
Provisioned successfully.
  Device: emulator-5554
  DO: true
  Restrictions: 17 applied
  /health: http://127.0.0.1:8181/health (via adb forward)
```

## Sample invocation

```
/provision-openwarden-emulator
/provision-openwarden-emulator --pair
```
