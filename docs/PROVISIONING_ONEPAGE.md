# OpenWarden — Device Setup (One Page)

**Print this. Follow it. Do not skip steps.**

---

## What you need

| Item | Notes |
|---|---|
| Pixel 7 / 7a / 7 Pro | Factory fresh or wiped clean |
| Laptop | Windows, macOS, or Linux |
| USB-C cable | Tested — not a charge-only cable |
| `adb` | `platform-tools` from Android SDK |
| `app-release.apk` + `app-release.apk.sha256` | From the OpenWarden release page |
| Parent's Google account email | Used to lock the device against unauthorized resets |
| Parent app | Installed on your phone before you start |

---

## Steps

**1. OOBE — stop before you sign in**
Power on the Pixel. Tap through "Hi there." Connect Wi-Fi.
When the Google account screen appears: **STOP. Do not sign in.**
Tap "Set up offline" (or skip). Finish the wizard with no account.

**2. Enable Developer Options and USB debugging**
Settings → About phone → tap Build number 7 times.
Settings → System → Developer options → enable USB debugging.
Plug in USB. Tap **Allow** when the RSA fingerprint prompt appears on the Pixel.

**3. Verify the device is clean**
```
adb shell settings get global device_provisioned   # must print 0
adb shell pm list-users                             # must show only Owner
adb shell dumpsys account | grep "Account {"        # must show nothing
```
Any result that does not match: factory reset, start over.

**4. Verify the APK hash, then install**
```
sha256sum app-release.apk   # must match app-release.apk.sha256
adb install -r -g app-release.apk
```
Stop if the hash does not match. Never install an unverified APK.

**5. Set Device Owner (one-shot — no undo without a wipe)**
```
adb shell dpm set-device-owner com.openwarden.child/.AdminReceiver
```
Expected output: `Success: Device owner set to package ComponentInfo{...}`

**6. Wait for restrictions to lock in**
The OpenWarden app locks the device in airplane mode and applies all restrictions.
Run:
```
adb shell content query \
  --uri content://com.openwarden.child.debug/health \
  --projection do_set:restrictions_count:fail_closed
```
Wait until it prints `do_set=true, restrictions_count=17, fail_closed=true`.
If this times out after 30 seconds: wipe and retry from step 1.

**7. Bind FRP in the parent app — do this last**
In the parent app, enter your Google account email and tap "Bind FRP."
Confirm on the laptop:
```
adb shell content query \
  --uri content://com.openwarden.child.debug/health \
  --projection frp_bound
```
Must print `frp_bound=true`.

> **Warning:** After this step, a factory reset requires your Google account OR your
> recovery phrase. Without one of those, a wiped device is unrecoverable.

**8. Pair with the parent app**
The Pixel displays a QR code. Scan it with the parent app.
The parent app verifies the hardware certificate chain and pushes the first policy bundle.
Pairing failed? Re-display the QR and scan again — it is safe to retry.

**9. Add the kid's Google account**
In the parent app, tap "Grant account add (60 s)."
Sign into the kid's Google account on the Pixel within 60 seconds.

**10. Print the recovery phrase, then hand over the phone**
The parent app shows the 24-word recovery phrase once.
Write it down or print it. Store it somewhere safe — not on this phone.
Unplug the USB cable. Disable airplane mode. Done.

---

## If something goes wrong

| Stuck at step | Fix |
|---|---|
| 1–3 | Power off, start over — nothing is saved yet |
| 4 | Re-download APK, re-verify hash, retry install |
| 5 | `fastboot -w`, start over from step 1 |
| 6 | `fastboot -w`, start over from step 1 |
| 7 (FRP bind failed) | `fastboot -w` is still safe — retry from step 7 |
| 7 (FRP bound, later step failed) | Use your Google account or recovery phrase |
| 8–9 | Retry — pairing and account-add are safe to repeat |

Full troubleshooting: [`docs/PROVISIONING.md`](PROVISIONING.md)
Engineering spec: [`docs/PROVISIONING_V2.md`](PROVISIONING_V2.md)

---

## What NOT to do

- Do not sign into any Google account before step 5
- Do not skip the APK hash check
- Do not skip printing the recovery phrase
- Do not unplug the USB cable before step 10

---

*OpenWarden — local-only, no subscription, no telemetry. Apache 2.0.*
