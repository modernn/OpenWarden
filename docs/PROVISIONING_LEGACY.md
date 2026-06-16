# Provisioning (Device Owner setup)

**Critical:** Device Owner mode can ONLY be set on a factory-fresh device with no Google accounts present. One wrong tap during the setup wizard (e.g., signing into a Google account) and you must `fastboot -w` and start over.

## Prerequisites
- Pixel 7+ (or any stock Android 13+ device)
- Bootloader **locked** (factory state) — recommended for FRP support
- adb installed on a PC
- USB cable

## Steps

1. **Factory reset / unbox**
   - New device: skip to step 2
   - Used device: Settings → System → Reset → Erase all data

2. **Boot to OOBE setup wizard**
   - Tap through "Hi there" screen
   - Connect to Wi-Fi
   - **STOP** at the "Add account" screen. Do NOT sign in.

3. **Enable Developer Options + USB debugging**
   - Tap "Set up offline" (or equivalent — skip account setup)
   - Complete wizard *without* adding any account (you can add accounts later as Device Owner)
   - Settings → About phone → tap Build number 7×
   - Settings → System → Developer options → enable USB debugging
   - Connect to PC, accept RSA prompt

4. **Install OpenWarden child APK**
   ```bash
   adb install child-android/app/build/outputs/apk/release/app-release.apk
   ```

5. **Set Device Owner** (the one-shot command)
   ```bash
   adb shell dpm set-device-owner com.openwarden.child/.AdminReceiver
   ```
   Expected output:
   ```
   Success: Device owner set to package ComponentInfo{...}
   ```
   If you see `IllegalStateException: Trying to set the device owner, but device owner is already set` or `Not allowed to set the device owner because there are already several users on the device` — you missed step 2. Factory reset, try again.

6. **Verify**
   ```bash
   adb shell dpm list-owners
   ```
   Should list OpenWarden as Device Owner.

7. **Pair with parent device**
   - Open OpenWarden app on child device
   - QR code displays: contains Tailscale auth key + child pubkey
   - Parent app scans
   - Parent pushes initial policy bundle
   - OpenWarden applies — restrictions go live

8. **Print the recovery phrase** (parent app shows once)
   - 24-word BIP39 mnemonic
   - Stash in a physical safe place
   - **Without it, a factory reset bricks the phone** (FRP blocks setup without your Google account; OpenWarden blocks factory reset; recovery phrase is the only escape)

## What NOT to do
- Don't sign into a Google account before step 5
- Don't set up biometric/PIN before step 5
- Don't `dpm set-device-owner` after enrollment — it's one-shot per factory state
- Don't skip the recovery phrase print

## Removing Device Owner (the legitimate way)
Parent app → Settings → "Decommission device" → enter recovery phrase → signed teardown command → DPC voluntarily releases DO + factory resets. Phone returns to fresh state.

## Bench testing
Before deploying to the real kid phone, run all of this on a **second device** (or use an emulator with `-feature DeviceAdmin`). Verify:
- Emergency dialer works from lock screen (do NOT actually dial 911 — verify dialer UI reaches the call button)
- Phone calls placed normally
- Selected apps blocked / unblocked
- Factory-reset attempt from Settings is denied
