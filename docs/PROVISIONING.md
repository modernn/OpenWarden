# PROVISIONING.md — Canonical Provisioning Runbook

> **Status:** Current canon. Read this if you are provisioning a Pixel 7 for production
> use. The engineering specification that underpins this runbook is
> [`PROVISIONING_V2.md`](PROVISIONING_V2.md). The legacy quick-start is preserved at
> [`PROVISIONING_LEGACY.md`](PROVISIONING_LEGACY.md) for reference only.

---

## Before you start

**What you need:**

- A Pixel 7, 7a, or 7 Pro — factory fresh or freshly wiped
- A laptop (Windows, macOS, or Linux) with `adb` installed
- A USB cable (USB-C)
- The OpenWarden child APK (`app-release.apk`) and its hash file (`app-release.apk.sha256`)
- The parent's Google account email address (for FRP binding — see S7 below)
- The OpenWarden parent app installed on the parent's phone

**Things that will immediately require a factory reset:**

- Signing into any Google account during OOBE before Device Owner is set
- Adding a second user before Device Owner is set
- Running `dpm set-device-owner` on an already-provisioned device

If any of those happened, go straight to [Recovery](#recovery-table).

---

## State machine overview

```
S0 BOXED
  → S1 OOBE_NETWORK
    → S2 DEV_OPTIONS_ON
      → S3 ADB_AUTHORIZED
        → S4 APK_INSTALLED
          → S5 DO_SET
            → S6 RESTRICTIONS_APPLIED  ← hot zone starts here
              → S7 FRP_BOUND           ← hot zone ends here; no easy rollback after this
                → S8 PAIRED_WITH_PARENT
                  → S9 KID_ACCOUNT_ADDED
                    → S10 OPERATIONAL
```

S5 through S7 are the hot zone. The provisioning script holds the device in airplane mode
for the entire hot zone and does not release it until S10 verification passes. A kid who
picks up the phone during this window cannot reach Settings or the OOBE account screen.

---

## Step-by-step

### S0 → S1: Verify factory state

Power on the device. Work through the "Hi there" screen. Connect to Wi-Fi.

**Stop at the Google account screen. Do not sign in.**

Verify via adb that the device is factory-fresh:

```bash
adb shell settings get global device_provisioned
# Must print: 0
# If 1: factory reset required before continuing.

adb shell pm list-users
# Must show only: UserInfo{0:Owner:13} running
# Any additional users: factory reset required.

adb shell dumpsys account | grep -i "Account {"
# Must show no rows.
# Any account present: DO cannot be set; factory reset required.
```

### S1 → S2: Enable Developer Options

On the device:

1. Tap "Set up offline" (or the equivalent skip-account option in the wizard).
2. Complete the wizard without adding any account.
3. Settings → About phone → tap **Build number 7 times**.
4. Settings → System → Developer options → enable **USB debugging**.

### S2 → S3: Authorize ADB

Connect USB cable. The device will prompt "Allow USB debugging?" — tap **Allow**.

```bash
adb devices
# Must show: <serial>  device
# "unauthorized": accept the RSA prompt on the device screen.
# "offline": reseat the cable or try a different port.
```

### S3 → S4: Install the APK (verify hash first)

```bash
EXPECTED=$(cat app-release.apk.sha256)
ACTUAL=$(sha256sum app-release.apk | awk '{print $1}')
[ "$EXPECTED" = "$ACTUAL" ] || { echo "APK HASH MISMATCH — abort"; exit 41; }

adb install -r -g app-release.apk
# Expected: Performing Streamed Install ... Success
# -g pre-grants all declared runtime permissions.

adb shell pm list packages | grep com.openwarden.child
# Expected: package:com.openwarden.child
```

Never skip the hash check. A tampered APK that passes `dpm set-device-owner` owns the
device.

### S4 → S5: Set Device Owner

This command is one-shot and cannot be undone without a factory reset.

```bash
adb shell dpm set-device-owner com.openwarden.child/.AdminReceiver
# Expected: Success: Device owner set to package ComponentInfo{...}
```

Common failures:
- `IllegalStateException: device owner is already set` → factory reset.
- `Not allowed to set the device owner because there are already several users` → an
  account was added during OOBE; factory reset.

### S5 → S6: Verify restriction application

The script enables airplane mode immediately after S5 and polls the debug health provider
to confirm the DPC applied all restrictions synchronously inside
`onProfileProvisioningComplete()`:

```bash
adb shell cmd connectivity airplane-mode enable

for i in $(seq 1 30); do
  STATUS=$(adb shell "content query \
    --uri content://com.openwarden.child.debug/health \
    --projection do_set:restrictions_count:fail_closed" 2>/dev/null)
  echo "$STATUS" | grep -q "do_set=true, restrictions_count=17, fail_closed=true" && break
  sleep 1
done
[ "$i" -lt 30 ] || { echo "S6 timed out — restrictions not applied"; exit 56; }
```

If this times out: the device is DO-set but restrictions failed. Factory reset and retry.
Do not advance to S7.

> The debug health provider (`content://com.openwarden.child.debug/health`) is present
> only in debug builds. Release builds compile it out. CI runs against debug builds;
> final hardware verification uses the same endpoint. See
> [PROVISIONING_V2.md §9](PROVISIONING_V2.md) for the full health payload.

### S6 → S7: Bind FRP (bind last — this is non-negotiable)

FRP is bound by the DPC via a signed bundle from the parent app; adb cannot bind it
directly. In the parent app, enter the parent's Google account email and tap "Bind FRP."
The parent app pushes a signed bundle; the DPC calls
`setFactoryResetProtectionPolicy(...)` with the email.

Confirm from the host:

```bash
adb shell "content query \
  --uri content://com.openwarden.child.debug/health \
  --projection frp_bound"
# Expected: frp_bound=true
```

**From this point forward, a factory reset requires the parent's Google account OR the
BIP39 recovery phrase.** If neither is available and the device bricks, it is e-waste.
This warning is displayed in the provisioning GUI before S7 proceeds.

### S7 → S8: Pair with parent

The child app displays a QR code encoding:

- Child Ed25519 public key
- Child X25519 public key
- Android Key Attestation cert chain (rooted in Google)
- Single-use pairing nonce

The parent app scans the QR, verifies the attestation cert chain is rooted in Google's
hardware attestation root, pins the child public keys, and pushes the parent's Ed25519
public key plus the initial signed policy bundle.

Pairing failures do not modify device state. Re-display the QR and re-scan.

Optional: capture the QR for CI / headless flows:

```bash
adb exec-out screencap -p > pairing_qr.png
```

### S8 → S9: Add the kid's Google account

The parent app issues a 60-second signed grant that temporarily relaxes
`DISALLOW_MODIFY_ACCOUNTS`. Within that window, the kid (or parent, during provisioning)
signs into the kid's Google account. The DPC reasserts the restriction immediately after
the account is added.

```bash
adb shell dumpsys account | grep "com.google" | wc -l
# Expected: 1
```

The grant has a 60-second TTL. If the window closes before the account is added, retry
from the parent app.

### S9 → S10: Final verification

```bash
adb shell "content query \
  --uri content://com.openwarden.child.debug/health"
```

All fields must be green. The provisioning script exits 0 only when every field matches
expected. Unplug the USB cable. Disable airplane mode. Hand the phone to the kid.

---

## Reference script

The canonical POSIX script is `scripts/provision-emulator.sh` (emulator target) with the
production-equivalent annotated skeleton in [PROVISIONING_V2.md §7](PROVISIONING_V2.md).

The production script (`provisioning/provision.sh`) and its PowerShell wrapper
(`provisioning/provision.ps1`) are the reference implementations. Both accept:

```
provision.sh <device-serial> <parent-google-email>
```

Exit codes: `1x` host/argument errors, `4x` artifact/signature errors, `5x`
device-state errors, `6x` verification timeouts. CI greps exit codes to surface the
failing state.

The script writes `~/.openwarden/last_state.json` on each transition. Re-running after a
crash resumes from the last verified state for S0–S4; for S5+ the script prompts before
continuing.

---

## Recovery table

| Failure at | Recovery |
|---|---|
| S0–S2 | Power off, re-OOBE. No persistent state. |
| S3 | `adb kill-server && adb start-server`; replug; re-accept RSA on device. |
| S4 | `adb uninstall com.openwarden.child`; re-verify APK hash; retry install. |
| S5 | `fastboot -w` (bootloader unlocked pre-FRP); restart from S0. |
| S6 | `fastboot -w`. Do not attempt in-place recovery at this state. |
| S7 (FRP bind failed) | FRP not committed; `fastboot -w` still safe. |
| S7 (FRP bind succeeded, later step failed) | Recovery requires parent's Google account OR BIP39 phrase. |
| S8 | Pairing is non-destructive. Re-scan QR. |
| S9 | Grant TTL expired. Retry account-add from parent app. |
| S10 | Normal decommission via parent app (phrase-gated). See below. |

---

## Removing Device Owner (legitimate decommission)

Parent app → Settings → "Decommission device" → enter recovery phrase → signed teardown
command → DPC voluntarily releases DO and factory resets. The phone returns to factory
state.

Without the recovery phrase: the parent's Google account can clear FRP after a
`fastboot -w`, but the `fastboot -w` itself is blocked while DO + FRP are both active
(the device locks Recovery mode). The phrase is the only exit that does not require a
Google account plus physical Pixel recovery cable procedure.

**Print the recovery phrase.** The parent app shows it once at the end of S8.

---

## Emulator path (development)

Full details in [PROVISIONING_V2.md §4](PROVISIONING_V2.md). Summary:

```bash
# Create AVD
avdmanager create avd -n openwarden_pixel7 \
  -k "system-images;android-34;google_apis;x86_64" \
  --device "pixel_7" --force

# Boot with DeviceAdmin feature enabled (required for dpm set-device-owner)
emulator -avd openwarden_pixel7 -feature DeviceAdmin \
  -no-snapshot -wipe-data -no-boot-anim -gpu swiftshader_indirect
```

The emulator covers most of the provisioning surface. It cannot test hardware
attestation, StrongBox, or real FRP. Hardware verification is required before any v1
release tag.

The `scripts/provision-emulator.sh` script runs against the booted emulator and handles
the `device_provisioned=1` emulator quirk automatically.

---

## Security reminders

- **No secrets in logs.** The provisioning script never logs the BIP39 phrase, private
  keys, pairing nonces, or sealed-box ciphertext. `set -x` is gated behind
  `OPENWARDEN_DEBUG=1` and refuses to run if it detects output to a non-`/tmp` path.
- **ADB key is sensitive.** `~/.android/adbkey` lets anyone with the file impersonate
  the parent's trusted ADB host. Keep the provisioning laptop encrypted at rest.
- **Do not copy-paste `dpm set-device-owner` from untrusted sources.** The component
  name `com.openwarden.child/.AdminReceiver` is pinned in the script. A wrong component
  name produces a confusing error and wastes a factory-reset cycle.
- **APK hash is mandatory.** The SHA-256 check guards against supply-chain compromise.
  Never skip it.

---

## References

- [PROVISIONING_V2.md](PROVISIONING_V2.md) — engineering spec, state machine,
  atomicity guarantees, security analysis
- [PROVISIONING_LEGACY.md](PROVISIONING_LEGACY.md) — original quick-start (historical)
- [DEFENSES.md](DEFENSES.md) — full `DISALLOW_*` restriction set
- [ATTACKS.md](ATTACKS.md) — attack surface and mitigations
- [CRYPTO.md](CRYPTO.md) — key derivation, pairing crypto, sealed-box event log
- [RECOVERY.md](RECOVERY.md) — BIP39 phrase management and device recovery
- `scripts/provision-emulator.sh` — emulator provisioning script
- [AOSP Managed Provisioning](https://source.android.com/docs/devices/admin/managed-provisioning)
- [`DevicePolicyManager` API](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)
