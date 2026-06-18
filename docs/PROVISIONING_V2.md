# Provisioning V2 — Atomic, Hardware-Attested, Emulator-Friendly

> **Status:** This document supersedes [`PROVISIONING.md`](PROVISIONING.md) as the canonical provisioning reference. The original remains as a quick-start cheat sheet; this version is the engineering spec.
>
> **Locked decisions (do not relitigate):** Pixel 7 child target, stock Android, locked bootloader, Apache 2.0 OSS, emulator path required for v1 dev (no bench Pixel), atomic provisioning (no kid-reachable window where DO unset), hardware-attested pairing via Android Key Attestation cert chain.
>
> **Amended by ADR-026 + ADR-027 (Accepted, 2026-06-17):** two of the locked decisions above are relaxed for the **consumer** path. (1) **Device scope** broadened from Pixel-only to Pixel + Samsung (S22+/A55+/Note) + OnePlus 11+ committed at the v1.0 release (ADR-026), so the StrongBox-only crypto + Google-only attestation assumptions become tiered (StrongBox→TEE fallback, per-OEM attestation roots — see ANDROID_COMPAT §3/§4). (2) **The ADB/USB state machine below is no longer the only path:** ADR-027 makes **QR-OOBE Device-Owner provisioning (app fetched from the Play Store, no computer) the primary consumer path**, and keeps this ADB/USB S0–S10 machine as the **power-user / bench / CI** path. **FRP-last and the attested-pairing model carry over to the QR path** (the latter per ADR-029, the landed Tier-2 amendment); the **I3-window atomicity is *re-derived*, not inherited verbatim** — the no-tether QR path replaces this doc's USB-tether layers (host airplane-mode + `/health` poll) with a full-screen provisioning hold + an on-device self-check (ADR-027 D5). The emulator path stays required for dev. Read this doc as the ADB/bench reference; read ADR-027 for the QR-OOBE consumer flow.

The v1 provisioning flow is the single highest-leverage attack surface in OpenWarden. Every restriction, every signed bundle, every defense in [`DEFENSES.md`](DEFENSES.md) is downstream of "is the DPC actually the Device Owner of a verified-boot Pixel 7 paired with the right parent?" Get provisioning wrong once and the rest of the security model is theater. This document specifies the full state machine, the exact commands, the atomicity guarantees, the emulator workflow, the parent-facing UI plan, and the failure-recovery procedures.

---

## 1. State machine

```
S0 BOXED → S1 OOBE_NETWORK → S2 DEV_OPTIONS_ON → S3 ADB_AUTHORIZED →
S4 OPENWARDEN_APK_INSTALLED → S5 DO_SET → S6 DAY_ONE_RESTRICTIONS_APPLIED →
S7 FRP_BOUND → S8 PAIRED_WITH_PARENT → S9 KID_GOOGLE_ACCOUNT_ADDED →
S10 OPERATIONAL
```

| State | Precondition | Advance command | Rollback path | Attacker window |
|---|---|---|---|---|
| **S0 BOXED** | Sealed device or post-`fastboot -w` | Power on, complete "Hi there" | `fastboot -w` (requires unlocked bootloader; not applicable post-S7) | None — kid not in possession |
| **S1 OOBE_NETWORK** | S0 + setup wizard at Wi-Fi screen | Connect Wi-Fi, **STOP at account screen** | Reboot to redo OOBE (no state persisted) | None |
| **S2 DEV_OPTIONS_ON** | S1 + "Set up offline" selected; **no Google account added** | Build number ×7, then enable USB debugging | Settings → Developer options → off (resets dev flag only) | None — pre-handoff |
| **S3 ADB_AUTHORIZED** | S2 + cable connected | Accept RSA fingerprint prompt on device | `adb kill-server` + revoke USB debug auth | If laptop ADB key is compromised, attacker who later steals device could push pre-S5; mitigated by S5 occurring seconds after S3 |
| **S4 OPENWARDEN_APK_INSTALLED** | S3 | `adb install -r app-release.apk` (signature-pinned) | `adb uninstall com.openwarden.child` | APK supply-chain compromise — verify SHA-256 before install (§8) |
| **S5 DO_SET** | S4 + **zero Google accounts, zero users beyond owner** | `adb shell dpm set-device-owner com.openwarden.child/.AdminReceiver` | Cannot rollback in-place; requires `fastboot -w` (only possible because S7 has not yet bound FRP) | **I3 OOBE reprovision window.** Between S5 success and S6 completion, DO is set but restrictions are not yet applied. Kid with phone in hand could open Settings. Mitigated by §3 atomicity. |
| **S6 DAY_ONE_RESTRICTIONS_APPLIED** | S5 + DPC `onProfileProvisioningComplete` returned successfully | DPC applies the full `DISALLOW_*` set synchronously inside the receiver; provisioning script polls `dpm list-owners` + a `/health` debug endpoint | Phrase-gated `setUserRestriction(..., false)` via parent push; or `fastboot -w` (still possible — S7 not bound) | Same I3 window if `onProfileProvisioningComplete` returns before restrictions land. Must be synchronous (§3). |
| **S7 FRP_BOUND** | S6 verified + parent Google account string supplied | DPC calls `setFactoryResetProtectionPolicy(FactoryResetProtectionPolicy.Builder().setFactoryResetProtectionAccounts([parent_email]).setFactoryResetProtectionEnabled(true).build())` | **Phrase-gated unbind only.** Once bound, `fastboot -w` produces a brick recoverable only by the FRP account or recovery phrase. | Pre-S7 brick is annoying (re-OOBE); post-S7 brick is catastrophic. **Bind FRP last** (see §6). |
| **S8 PAIRED_WITH_PARENT** | S7 + parent app running, both devices on shared LAN or BLE | Child generates StrongBox Ed25519 + X25519 keypairs with nonce-challenge attestation; QR encodes `(child_pub_ed25519, child_pub_x25519, attestation_cert_chain, pairing_nonce)`; parent scans, verifies cert chain rooted in Google, pins child pubkeys + pushes parent pubkey + initial signed policy bundle | Phrase-gated rotation via §15 in `DEFENSES.md` | H3 pubkey substitution if attestation cert chain not validated parent-side; QR replay if nonce not single-use |
| **S9 KID_GOOGLE_ACCOUNT_ADDED** | S8 + parent-signed bundle that temporarily relaxes `DISALLOW_MODIFY_ACCOUNTS` for a single account-add transaction | Parent app issues one-shot signed grant; child DPC flips restriction off, observes Account Manager add, flips back on | Restriction re-asserted by DPC heartbeat; account remains | E4 if grant window leaks; mitigated by 60-second TTL on grant |
| **S10 OPERATIONAL** | S9 | Hand phone to kid | Phrase-gated decommission per `PROVISIONING.md` §"Removing Device Owner" | All A/C/D/E/F/G/H/I/J/K attacks per `ATTACKS.md` — defended per `DEFENSES.md` |

The hot zone is **S5–S7**. From the moment `dpm set-device-owner` succeeds until FRP is bound, the device is fully owned by OpenWarden but not yet sealed against `fastboot -w`. Atomicity (§3) closes the kid-reachable variant; FRP-last ordering (§6) closes the brick-risk variant.

---

## 2. Exact ADB commands per state

The reference script is `provisioning/provision.sh` (POSIX) with a PowerShell wrapper at `provisioning/provision.ps1` for Windows hosts. Both call the same underlying `adb` binary.

```bash
# ---- S0 → S1: Verify factory fresh ----
adb shell settings get global device_provisioned
# Expected: 0
# If 1: device is post-OOBE. Either factory reset (fastboot -w, bootloader must be
# unlocked — not the v1 happy path) or use a fresh device.

adb shell pm list-users
# Expected: only "UserInfo{0:Owner:13} running"
# If additional users present: factory reset required.

adb shell dumpsys account | grep -i "Account {"
# Expected: no rows.
# If any account present: DO cannot be set; factory reset required.

# ---- S1 → S2: Confirm dev options + USB debug ----
# (Manual taps on device, then:)
adb devices
# Expected: <serial>  device
# Failure mode: <serial>  unauthorized → user must accept RSA prompt on device (S3)
# Failure mode: <serial>  offline → cable / cable port issue

# ---- S3 → S4: Install OpenWarden APK (signature-pinned) ----
EXPECTED_SHA256="<pinned in script; matches release artifact>"
ACTUAL_SHA256=$(sha256sum app-release.apk | awk '{print $1}')
[ "$EXPECTED_SHA256" = "$ACTUAL_SHA256" ] || { echo "APK HASH MISMATCH — abort"; exit 41; }

adb install -r -g app-release.apk
# Expected: Performing Streamed Install ... Success
# -g grants all runtime permissions declared in manifest (saves a tap later)

adb shell pm list packages | grep com.openwarden.child
# Expected: package:com.openwarden.child

# ---- S4 → S5: Set Device Owner (the one-shot) ----
adb shell dpm set-device-owner com.openwarden.child/.AdminReceiver
# Expected: Success: Device owner set to package ComponentInfo{...}
# Failure: java.lang.IllegalStateException: Trying to set the device owner, but
#   device owner is already set → factory reset required.
# Failure: Not allowed to set the device owner because there are already several
#   users on the device → an account leaked in despite OOBE care; factory reset.

# ---- S5 → S6: Apply day-one restrictions (DPC-side, observed from host) ----
# The DPC's onProfileProvisioningComplete() applies every restriction in
# DEFENSES.md row #2 synchronously, then writes a marker to a debug-only
# /health endpoint. The host script polls:
for i in $(seq 1 30); do
  STATUS=$(adb shell "content query --uri content://com.openwarden.child.debug/health --projection do_set:restrictions_count:fail_closed" 2>/dev/null)
  echo "$STATUS" | grep -q "do_set=true, restrictions_count=17, fail_closed=true" && break
  sleep 1
done
[ "$i" -lt 30 ] || { echo "S6 timed out — restrictions not applied"; exit 56; }

# ---- S6 → S7: Bind FRP ----
# Triggered by parent app pushing parent_google_email via signed bundle on the
# pairing channel. ADB does not bind FRP directly; the DPC calls
# setFactoryResetProtectionPolicy() with the email.
# Host script confirms via /health:
adb shell "content query --uri content://com.openwarden.child.debug/health --projection frp_bound"
# Expected: frp_bound=true

# ---- S7 → S8: Pair with parent ----
# Child generates StrongBox-attested keypairs and displays QR; parent scans.
# Host script optionally captures the QR via screencap for headless / CI flow:
adb exec-out screencap -p > pairing_qr.png

# ---- S8 → S9: Add kid Google account ----
# Parent app issues a 60s grant. Kid (or parent during provisioning) signs in.
adb shell dumpsys account | grep "com.google" | wc -l
# Expected: 1 (the kid's managed account)

# ---- S9 → S10: Final verification ----
adb shell "content query --uri content://com.openwarden.child.debug/health"
# Expected JSON-ish blob with all flags green; see §9.
```

Every transition has a non-zero exit code in the reference script; CI distinguishes "wrong state" (40-series) from "device fault" (50-series) from "policy verification failed" (60-series).

---

## 3. Atomic completion guarantee

The cardinal rule: **a kid never holds a phone where the DPC is partially configured.** Three layers enforce this.

**Layer 1 — Airplane mode + tethered execution.** The provisioning script enables airplane mode at the start of S2 (`adb shell cmd connectivity airplane-mode enable`) and does not disable it until S10 verification passes. The phone is physically tethered to the parent's laptop via USB throughout. There is no point in the flow where the device could be handed to a kid mid-script: it is a peripheral of the laptop until §1 row S10 is reached.

**Layer 2 — Synchronous restriction application inside `onProfileProvisioningComplete()`.** The DPC's `DeviceAdminReceiver.onProfileProvisioningComplete()` callback is the only safe place to apply day-one restrictions, because the AOSP `ManagedProvisioning` flow waits for it to return before declaring success. Inside that callback we apply all 17 `DISALLOW_*` user restrictions, plus `setUserControlDisabled(self, true)`, plus `setLockTaskPackages`, plus `setApplicationHidden("com.android.settings", true)`, plus `setAlwaysOnVpnPackage(self, true)`, plus `setGlobalPrivateDnsMode(...)`, plus the policy fail-closed flag — all on the calling thread, with each call wrapped in a try/catch that escalates any single failure to a hard `Log.wtf` and an explicit re-throw. If the receiver throws, AOSP rolls back the DO grant. If it returns, the host script proceeds, and the device is fully locked.

**Layer 3 — Host-side verification of the `/health` content provider.** The debug-only `content://com.openwarden.child.debug/health` provider (compiled out of release builds — see §9) returns a structured snapshot of DO status, restriction set, pubkey-pin status, fail-closed posture, and FRP binding. The host script does not advance from S6 → S7 until the provider returns the expected fingerprint, and does not declare S10 until every field is green. A mid-script device handoff to a kid is impossible because the script never releases the USB cable until all three layers report success.

Edge case: a kid grabs the phone off the table while the script is running. Because the device is in airplane mode, `setUserControlDisabled` is already in force from the start of S6, and Settings is hidden, the kid sees the OOBE-completion launcher or the in-progress OpenWarden activity. They cannot reach the OOBE account-add screen because that screen does not return once OOBE completes. The only "useful" action available is power-off, which on next boot resumes into the now-DO-owned state.

---

## 4. Emulator path

V1 development without a bench Pixel runs on the Android Emulator. The emulator covers ~70% of the provisioning surface; the remaining 30% (hardware attestation, FRP, AVB) must be exercised on real hardware before release.

**AVD setup:**

```bash
# SDK + image
sdkmanager "platform-tools" "platforms;android-34" "system-images;android-34;google_apis;x86_64"

# AVD — Pixel 7 profile, API 34
avdmanager create avd -n openwarden_pixel7 -k "system-images;android-34;google_apis;x86_64" \
  --device "pixel_7" --force

# Boot with device-admin feature enabled
emulator -avd openwarden_pixel7 -feature DeviceAdmin -no-snapshot -wipe-data \
  -no-boot-anim -gpu swiftshader_indirect
```

The `-feature DeviceAdmin` flag is required; without it `dpm set-device-owner` returns "Trying to set the device owner, but device owner is already set" because the emulator pre-creates managed accounts on some images.

**What the emulator CAN test:**
- Full DPC restriction set (every `DISALLOW_*` from `DEFENSES.md` row #2)
- `setApplicationHidden`, `setUserControlDisabled`, `setLockTaskPackages`, `setAlwaysOnVpnPackage`, `setGlobalPrivateDnsMode`
- Allowlist enforcement, time-window enforcement, monotonic clock behavior
- Signed bundle verification (Ed25519 sig check, `policy_seq` regression rejection)
- Parent↔child sync protocol over emulator LAN bridge
- Kid UX: lock-task mode, request flows, audit log UI, sealed-box envelope generation
- Heartbeat + silence alarm logic (kill the emulator network to simulate offline)

**What the emulator CANNOT test:**
- Android Verified Boot attestation — emulator returns `verifiedBootState=VERIFIED` from a software root, not a Google cert chain. Tests that assert "cert chain rooted in Google" will fail by design; use `OPENWARDEN_ATTESTATION_MODE=emulator` env flag to accept the software root in dev builds only.
- StrongBox-backed key generation — emulator has TEE-only Keystore, no StrongBox. Use `setIsStrongBoxBacked(false)` fallback in dev builds; assert StrongBox in release builds.
- Factory Reset Protection — `setFactoryResetProtectionPolicy` returns success but no real FRP partition exists. Manual hardware test required.
- True bootloader-locked behavior — emulator boots from a writable image.
- Real Titan M2 anti-rollback counters and per-boot key wrapping.

**CI integration.** GitHub Actions runs `reactivecircus/android-emulator-runner@v2` with the Pixel 7 profile. The workflow runs `provisioning/provision.sh` against the booted emulator, then runs the smoke test from §9. Total runtime ~8 minutes per PR. The workflow file lives at `.github/workflows/provisioning-smoke.yml` and gates merges to `main`.

---

## 5. Provisioning UI for non-tech parents

ADB is not a long-term parent-facing surface. Three options were evaluated:

**Option A — Desktop GUI tool ("OpenWarden Provisioner").** A Tauri-Mobile-Desktop or PyQt single-window app that wraps the `provision.sh` script. Parent plugs Pixel into laptop via USB, clicks "Provision," watches a progress bar, done. Distribute as code-signed binaries for macOS/Windows/Linux plus source. Pros: works with any Pixel 7 from any retailer, no parent-phone prerequisite, easy to debug because it's just a wrapper over the canonical script. Cons: requires a laptop, requires USB cable + working OEM driver on Windows, distribution requires three signed binaries.

**Option B — NFC-bump provisioning.** Parent phone runs a OpenWarden parent app that emits an AOSP-standard `android.app.action.PROVISION_MANAGED_DEVICE` intent payload via NFC. Kid Pixel at OOBE "Tap two phones together" screen receives it and auto-installs the DPC. Pros: zero laptop. Cons: requires parent phone to be NFC-capable Android (excludes iPhone parents), requires OpenWarden parent app to already be installed before any provisioning has happened, and the NFC handshake is well-documented as fragile across OEM/Android-version combinations.

**Option C — QR-code provisioning via `EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME` payload.** During OOBE, the kid Pixel's setup wizard supports "Scan a QR code" as a managed-provisioning entry point. The QR encodes the DPC component name, download URL, signature checksum, and Wi-Fi credentials. Parent app on parent phone (iPhone or Android) displays the QR. Pros: works with any parent phone, zero cable, AOSP-standard path. Cons: requires the QR to embed a Wi-Fi password (parent UX friction) and requires the DPC APK to be hosted at a stable HTTPS URL (OpenWarden GitHub Releases works for v1).

**Recommendation for v1: Option A (Desktop GUI tool), shipped as a PyInstaller binary wrapping a Python rewrite of `provision.sh`.** Rationale: v1's target user is a technically-inclined parent who is already comfortable plugging a phone into a laptop — they are the same population that will tolerate the Apache 2.0 / FOSS / "build from source if you want" tradeoff. Option C is the right v2 move once the DPC is mature enough that a hosted-APK supply chain is worth the ops cost. Option B is interesting but the iPhone-parent exclusion is disqualifying.

The v1 GUI is a single window: device-detection indicator, "Parent's Google account for FRP" text field, "Provision" button, progress log, "Print Recovery Phrase" final step. Source lives in `provisioning/gui/`. Distribution: GitHub Releases, three signed artifacts.

---

## 6. Provisioning failure recovery

The brick risk is not uniform across the state machine. Pre-S7, `fastboot -w` recovers any failure. Post-S7, `fastboot -w` produces a phone that boots to the FRP screen and demands the parent's Google account; without that account *and* without the BIP39 recovery phrase, the device is e-waste.

**Therefore: bind FRP last.** S7 is the very last DPC-side operation that materially changes the recoverability profile. The script ordering — restrictions before FRP — is not negotiable.

**Per-state recovery:**

| Failure at | Recovery |
|---|---|
| S0–S2 | Power off, re-OOBE. No persistent state. |
| S3 | `adb kill-server && adb start-server`; replug cable; re-accept RSA. |
| S4 | `adb uninstall com.openwarden.child` (works pre-DO); re-verify APK hash; retry. |
| S5 | `fastboot -w` (bootloader still effectively unlocked from kid's perspective — no FRP). Then restart from S0. |
| S6 | `fastboot -w`. The DPC is set but restrictions did not apply; rather than try to recover in-place, wipe and retry. The script does not advance to S7 unless §3 verification passes, so this state is rare. |
| S7 | If FRP-bind itself fails, the policy is not committed; `fastboot -w` still safe. If FRP-bind succeeds but a later step fails, **the device is now FRP-bound** — recovery requires the parent's Google account (works because S7 binds the parent's account, not the kid's) OR the BIP39 phrase. Document this loudly in the GUI: "From this point forward, factory reset requires your Google account or recovery phrase." |
| S8 | Pairing failures are non-destructive; re-display QR, re-scan. Cert chain validation failures abort with a clear error; no rollback needed because S8 does not modify device state until parent confirms. |
| S9 | Account-add failures roll back automatically because the DPC's grant is 60s TTL. Retry. |
| S10 | This is the operational state; failures from here use the normal decommission path (phrase-gated). |

The script writes a `last_state.json` to the host on every transition. If the script crashes, re-running it reads the file and resumes from the last verified state — idempotent for S0–S4, manual rollback prompt for S5+. CI sets `OPENWARDEN_FAIL_FAST=1` to disable resume and require clean runs.

---

## 7. Provisioning script template

The reference script lives at `provisioning/provision.sh`. Annotated skeleton:

```bash
#!/usr/bin/env bash
set -euo pipefail

# --- Argument validation ---
[ $# -eq 2 ] || { echo "usage: provision.sh <device-serial> <parent-google-email>"; exit 10; }
DEVICE="$1"
FRP_EMAIL="$2"
echo "$FRP_EMAIL" | grep -qE '^[^@]+@[^@]+\.[^@]+$' || { echo "invalid email"; exit 11; }
adb -s "$DEVICE" get-state >/dev/null || { echo "device $DEVICE not found"; exit 12; }

# --- Logging (NOT to a file that could contain secrets) ---
LOG="$(mktemp -t openwarden-prov-XXXXXX.log)"
exec > >(tee -a "$LOG") 2>&1
trap 'echo "FAILED at line $LINENO (state=$STATE) — log at $LOG"' ERR

STATE="S0"
checkpoint() { STATE="$1"; echo "[$(date -u +%FT%TZ)] $STATE"; echo "$STATE" > "$HOME/.openwarden/last_state.json"; }

# --- S0 → S1 ---
checkpoint S0
[ "$(adb -s "$DEVICE" shell settings get global device_provisioned | tr -d '\r')" = "0" ] || { echo "device already provisioned"; exit 50; }

# ... (S1, S2, S3 sections — see §2) ...

# --- S4 ---
checkpoint S4
EXPECTED_SHA256=$(cat app-release.apk.sha256)
[ "$(sha256sum app-release.apk | awk '{print $1}')" = "$EXPECTED_SHA256" ] || { echo "APK HASH MISMATCH"; exit 41; }
adb -s "$DEVICE" install -r -g app-release.apk

# --- S5 ---
checkpoint S5
adb -s "$DEVICE" shell cmd connectivity airplane-mode enable
adb -s "$DEVICE" shell dpm set-device-owner com.openwarden.child/.AdminReceiver

# --- S6: poll /health for restriction application ---
checkpoint S6
for i in $(seq 1 30); do
  if adb -s "$DEVICE" shell "content query --uri content://com.openwarden.child.debug/health" | grep -q "restrictions_count=17"; then break; fi
  sleep 1
done
[ "$i" -lt 30 ] || exit 56

# --- S7: bind FRP (DPC-side, triggered via signed grant on pairing channel) ---
checkpoint S7
# ... parent app pushes FRP_EMAIL via signed bundle; DPC calls setFactoryResetProtectionPolicy ...

# --- S8 → S10 — see §2 ---
checkpoint S10
echo "PROVISIONING COMPLETE"
exit 0
```

Exit codes: `1x` argument/host errors, `4x` artifact/signature errors, `5x` device-state errors, `6x` verification timeouts. CI greps for exit code and surfaces the failing state.

---

## 8. Security gotchas

- **No secrets in logs.** The script logs the FRP email (acceptable — it's recoverable from parent's Google profile) and the parent's Ed25519 public key (acceptable — public by definition). It does **not** log: the BIP39 recovery phrase, the parent's X25519 private key, the StrongBox-wrapped child private keys, any pairing nonce after S8 commits, or any sealed-box ciphertext content. The `set -x` debug mode is gated behind `OPENWARDEN_DEBUG=1` and refuses to run if `provision.sh` detects it is recording to a non-`/tmp` path.
- **ADB authorization key is sensitive.** The host laptop's `~/.android/adbkey` lets anyone with the file impersonate the parent's ADB-trusted host. Document storing the laptop encrypted-at-rest; recommend a dedicated provisioning laptop or a borrowed-and-wiped one if paranoid. The key is not embedded in any artifact.
- **No BIP39 in provisioning artifacts.** The phrase is generated parent-side, displayed once in the parent app, and the parent prints/transcribes it. It is never sent to the child, never written to disk on the laptop, never logged. The DPC stores only a hash-commitment of the phrase derivation, used to verify subsequent phrase-gated commands.
- **APK signature verification before install.** The script compares SHA-256 of the APK against a pinned value (committed in the repo, signed in releases). Mismatch → abort. The release pipeline signs the APK with the OpenWarden release key and publishes the hash in `app-release.apk.sha256` alongside the APK.
- **No copy-paste of `dpm set-device-owner` from random docs.** The component name `com.openwarden.child/.AdminReceiver` is pinned in the script; a typo (e.g., wrong package) succeeds at OOBE-then-fails-at-DO-set and produces a confusing error that has historically caused people to mash `fastboot -w` repeatedly. Don't.

---

## 9. CI smoke test

After provisioning, the child app exposes a debug-build-only content provider at `content://com.openwarden.child.debug/health` returning:

```json
{
  "do_set": true,
  "restrictions_count": 17,
  "restrictions": ["DISALLOW_FACTORY_RESET", "DISALLOW_SAFE_BOOT", ...],
  "frp_bound": true,
  "frp_account_hint": "p***@gmail.com",
  "parent_pubkey_pinned": true,
  "parent_pubkey_attested": true,
  "fail_closed": true,
  "policy_seq": 1,
  "heartbeat_last_emitted": 1718467200,
  "strongbox_available": true,
  "always_on_vpn_pinned": "com.openwarden.child",
  "private_dns_pinned": "openwarden-resolver.example"
}
```

The provider is registered in `child-android/app/src/debug/AndroidManifest.xml` only; release builds do not compile the class. CI asserts every field matches expected. A failing assertion blocks the merge. Local dev can curl the provider via `adb shell content query`.

---

## 10. Test plan for v1

Before tagging v1.0.0:

1. **Emulator end-to-end.** `provisioning/provision.sh` against fresh Pixel 7 AVD, API 34. CI runs on every PR; release requires a green emulator run on the release commit.
2. **Real-hardware end-to-end.** Acquire one used Pixel 7a (target: ~$150 used market, or borrow from a friend with explicit "I might brick this" disclosure). Run full provisioning. Verify FRP actually engages by attempting `fastboot -w` and confirming the FRP screen demands the parent's Google account.
3. **A-class attack sweep.** Walk through every A-class attack in [`ATTACKS.md`](ATTACKS.md) §"Restriction audit" on the bench Pixel: Safe Mode boot, Settings → Factory reset, Recovery wipe, `fastboot flashing unlock`, `adb dpm` against authorized + unauthorized hosts, OEM unlock toggle, sideload, secondary account, second user, MTP pull, clock rollback, NFC bump, Force-stop, Clear-data. Each must produce the expected "blocked" UX.
4. **I-class provisioning attacks.** Specifically: I1 race the DPC during OOBE (verify atomicity §3 holds), I2 swap APK mid-install (verify SHA-256 check), I3 reach Settings during S5→S6 window (verify airplane mode + synchronous restriction application).
5. **K-class parent-UX surfacing.** Walk through every K-class attack in `ATTACKS.md` against the parent app: confirm K1 surfaces as a co-parent audit feed entry, K2 surfaces a 24h delay, K3 phrase-shoulder-surf surfaces a phrase-use audit entry.
6. **Seven-day uptime test.** Provision, hand to a stand-in user (a parent's own pocket), run for 7 days with normal use. Confirm: zero crashes, heartbeat continuous, OTA arrives and applies, restrictions hold post-OTA.
7. **Reboot + OTA simulation.** Drain battery to forced shutdown; reboot; verify DO + restrictions intact, FGS watchdog restarts, policy bundle reload succeeds, heartbeat resumes within 60s. Simulate OTA by sideloading an incremental update; verify post-OTA self-test (`DEFENSES.md` row 10) detects FGS-killed state and re-arms.

A v1 release that ships without (2) — a real-hardware end-to-end on a Pixel 7 family device — is not a v1 release. The emulator is necessary but not sufficient.

---

## References

- [AOSP Managed Provisioning](https://source.android.com/docs/devices/admin/managed-provisioning)
- [`DevicePolicyManager` API](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)
- [Android Emulator AVD configuration](https://developer.android.com/studio/run/managing-avds)
- [google/android-key-attestation sample](https://github.com/google/android-key-attestation) — parent-side cert chain verification reference
- [TestDPC](https://github.com/googlearchive/android-testdpc) — canonical DPC provisioning implementation; the airplane-mode-during-provisioning pattern is borrowed from here
- [Headwind MDM provisioning script](https://github.com/h-mdm/hmdm-server) — reference for production-grade `dpm`-driver shell scripts
- Companion docs: [`ATTACKS.md`](ATTACKS.md), [`DEFENSES.md`](DEFENSES.md)
