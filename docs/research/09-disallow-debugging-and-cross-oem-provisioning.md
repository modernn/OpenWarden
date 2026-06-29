# `DISALLOW_DEBUGGING_FEATURES` Analysis + Cross-OEM Device-Owner Provisioning Matrix

> **Status: research INPUT, NOT canon.** Per [`CLAUDE.md`](../../CLAUDE.md) doc-tier rules and
> [`docs/research/README.md`](README.md): this is raw research, not a decision. **No
> implementation without an ADR.** Anything touching crypto / `proto/` / policy / pairing is
> **agent-blocked** (human + ADR before any code). If this contradicts a canon doc, the canon
> doc wins.
> **Date:** 2026-06-29. **Method:** repo-context read (ADRs, ATTACKS.md, DEFENSES.md,
> PROTOCOL.md, PolicyEnforcer.kt, prior research 03-07) + primary-source review (AOSP javadoc,
> Jason Bayton DPC-allowlist/key-attestation posts, Samsung Knox docs, dontkillmyapp.com,
> Android Enterprise community bug reports). AI-generated; **human-verify before acting.**
> **Informs:** ADR-020 (fail-closed restrictions), ADR-026 (OEM release scope), ADR-027
> (provisioning model), ADR-029 (Tier-2 attestation posture). Does NOT supersede any of them.
> **Issues filed:** #131 (injectable restriction seam), #132 (setUserControlDisabled gap),
> #133 (Play Protect DPC allowlist), #134 (attestation root rotation to P-384), #135 (A55
> Knox Vault correction), #136 (OnePlus FGS Deep Optimization).

---

## Abstract

This report addresses two active engineering questions from the 2026-06-29 session. Thread 1
examines whether `DISALLOW_DEBUGGING_FEATURES` is redundant given other DPC restrictions, and
whether a build-conditional (debug-only) relaxation is safe. Verdict: keep it always-on, it is
not redundant, and relaxing it in any build variant manufactures a fail-open. A secondary
finding is that two belt-and-suspenders measures documented in DEFENSES.md
(`setUserControlDisabled` + `adb_enabled=0` global write) are not implemented in child-android
at the time of writing (filed as issue #132). Thread 2 examines testing and demo strategies
around the adb-dark constraint without weakening the release build, and proposes an injectable
`restrictionFilter` seam (issue #131) as the cleanest long-term solution. Thread 3 provides a
per-brand Device-Owner provisioning matrix covering Pixel, Samsung, OnePlus, Motorola, Nothing,
and Xiaomi under OpenWarden's local-only, no-SaaS constraints, and identifies two new
cross-cutting blockers not captured in the current ADRs: the Google Play Protect DPC allowlist
requirement (issue #133) and the attestation root rotation from RSA to ECDSA P-384 via Remote
Key Provisioning (issue #134).

---

## Thread 1 - Is DISALLOW_DEBUGGING_FEATURES Redundant? VERDICT: keep always-on.

### 1.1 What the restriction actually closes

`DISALLOW_DEBUGGING_FEATURES` (`UserManager.DISALLOW_DEBUGGING_FEATURES`) is applied in
`PolicyEnforcer.requiredRestrictionsForSdk()` at line 326 of
`child-android/app/src/main/kotlin/com/openwarden/child/PolicyEnforcer.kt` (the function
begins at line 322). When set by the Device Owner, Android sets `adb_enabled` to 0 and
prevents the user from re-enabling USB debugging through Developer Options or any Settings
path. This is the **only restriction in the required set that closes the adb console** from
the OS side.

No other restriction in the current required set has equivalent effect:

| Restriction | What it actually blocks | Does it block adb? |
|---|---|---|
| `DISALLOW_DEBUGGING_FEATURES` | USB debugging toggle + `adb_enabled` bit | **Yes -- this is the control.** |
| `DISALLOW_APPS_CONTROL` | Settings/launcher **UI** actions: clear data, force-stop, disable | **No.** AOSP javadoc explicitly notes the user "will still be able to perform those actions via other means (such as adb)." `adb shell pm clear <pkg>` and `adb shell am force-stop <pkg>` succeed with this restriction set and debugging on. |
| `DISALLOW_USB_FILE_TRANSFER` | MTP (Media Transfer Protocol) file browsing via USB | **No.** Does not cover `adb pull` / `adb push` -- those use the ADB daemon, not MTP. |
| `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` | UI consent screen for installing APKs from unknown sources | **No.** Does not block `adb install` / `adb shell pm install`, which bypass the UI consent gate entirely. |
| `DISALLOW_FACTORY_RESET` | Settings -> Factory Reset UI | **No** effect on adb. |
| `DISALLOW_SAFE_BOOT` | Safe-mode boot via long-power-button | **No** effect on adb. |

The implication: removing or relaxing `DISALLOW_DEBUGGING_FEATURES` while keeping every other
restriction would leave `adb shell pm clear`, `adb shell am force-stop`, `adb pull` (data
exfiltration), `adb push` (policy-state tampering), and `adb install` (sideload) all
functional, requiring only USB physical access.

### 1.2 DPC self-protection -- what IS protected and what is NOT

The DPC binary itself has OS-level uninstall protection once it is Device Owner:

- `adb uninstall com.openwarden.child` results in `DELETE_FAILED_DEVICE_POLICY_MANAGER`
- `adb shell dpm remove-active-admin` results in `SecurityException` (the DO cannot be
  removed via adb while active; only a factory reset clears it)

So the **package** cannot be removed via adb. However:

- The **runtime** (PolicyService, FGS watchdog, ApiServer) can be force-stopped via
  `adb shell am force-stop com.openwarden.child` if `DISALLOW_DEBUGGING_FEATURES` is off.
  Once the service is stopped the heartbeat ratchet (ADR-024) stops ticking, the restriction
  re-assert (ADR-021) stops firing, and the child is effectively unmonitored until the next
  reboot or watchdog wakeup.
- The **on-disk DataStore** (policy bundle, pinned pubkeys, replay floor) lives in the app's
  private data directory. With adb and debugging on, `adb pull /data/data/com.openwarden.child/`
  succeeds on a **non-rooted, bootloader-locked** device. An older permissive bundle or a
  crafted one can then be pushed back -- the K1 rollback attack.
- **File exfiltration** of the event log ciphertext is possible via `adb pull`. Content
  confidentiality is preserved (sealed-box encrypted to parent pubkey), but pulling and
  replaying old DataStore state is a real, non-root attack.
- **Sideload floor**: `adb install` works without the UI consent gate when `adb_enabled=1`,
  even when `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` is set.

### 1.3 Adversary model -- why this matters for a 9-13yo

ATTACKS.md section 1 establishes the declared threat: a motivated 9-13yo who "can follow
YouTube + run adb", with physical + USB access, non-root, bootloader-locked Pixel-class device.
This is within the declared in-threat-model envelope.

Two ATTACKS.md-rated attacks become non-root exploitable the moment `adb_enabled=1`:

| Attack | ATTACKS.md rating | Requires adb_enabled=1? |
|---|---|---|
| **K1** -- pull old DataStore, push back permissive policy bundle | **CRITICAL** | Yes. DataStore pull requires adb; no root needed on a non-rooted device. |
| **K2** -- event-log tail-truncation (adb push shorter log, reset `prev_hash` chain) | **HIGH** | Yes. `adb push` to the app data directory. |

Both attacks are currently rated under the assumption that `DISALLOW_DEBUGGING_FEATURES` is
on. Relaxing it promotes both from "requires the restriction to be bypassed first" to "free
given a USB cable." This alone disqualifies any relaxation.

### 1.4 Why a BuildConfig.DEBUG relaxation is rejected

A conditional such as `if (!BuildConfig.DEBUG) add(UserManager.DISALLOW_DEBUGGING_FEATURES)`
manufactures a distinct fail-open variant of the DPC. This violates the project
non-negotiable:

> "Fail-closed. Every error path defaults to MORE restriction, never less." (CLAUDE.md;
> ADR-020 D1)

A debug build with this restriction removed is not an "error path" -- it is a deliberately
weaker variant. The failure mode is real: a CI artifact signed with a debug key that
accidentally ships to a device, or a developer who installs the debug build on the child
device "just to check something." Either event leaves the child device adb-reachable. The
restriction is cheap to keep; there is no performance or dev-ergonomics cost to applying it
in every build variant. REJECTED.

### 1.5 Implementation gap: belt-and-suspenders measures are documented but not implemented

DEFENSES.md row 1 documents `setUserControlDisabled(self, true)` as shipped. ATTACKS.md
line 134 documents an `ADB_ENABLED=0` global write at provisioning as a second layer closing
attacks A5/A6.

A search of `child-android/app/src/main/kotlin/com/openwarden/child/` finds **neither** call
present in the current codebase:

- `setUserControlDisabled` -- not called anywhere in child-android main source.
- `Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)` or equivalent -- not
  present.

`DISALLOW_DEBUGGING_FEATURES` is therefore carrying the full adb-closure load without the
belt-and-suspenders that would handle edge cases (e.g., the restriction fires before
`setUserControlDisabled` on a given OEM, or `adb_enabled` persists across a partial factory
reset on some firmware variants). Filed as **issue #132**. The existing docs are correct; the
implementation is incomplete.

### 1.6 Per-OEM reliability caveat

`DISALLOW_DEBUGGING_FEATURES` is reliably enforced on Pixel (Tier 1 reference). On Tier-2
OEMs it is best-effort. Known risks:

- Samsung devices have historically had firmware variants where Developer Options re-enables
  adb after a Knox policy change is applied. This is not documented in current ADRs.
- OnePlus OxygenOS has at various points re-evaluated user restrictions after an OTA. The
  post-OTA re-assert (ADR-021 / DEFENSES.md row 10) is the intended mitigant but has not been
  bench-verified on OnePlus 11+.

Do not over-claim "adb is closed" as an absolute for Tier-2 OEMs without bench verification
(ADR-026 D5 / ADR-023 D4 bench QA gate).

---

## Thread 2 - Testing and Demo Around the adb-Dark Constraint

### 2.1 Why a redir to an enforcing child returned 0 bytes

During live demo work: `adb forward tcp:7180 tcp:7180` followed by `curl localhost:7180/health`
on a fully-enforcing (adb-dark) child returns connection refused or 0 bytes. The cause was NOT
the forward topology -- `adb forward` bridges host-to-guest cleanly and is operational regardless
of `DISALLOW_DEBUGGING_FEATURES` once the forward is set up before the restriction fires. The
actual cause was the Ktor/ApiServer not answering because `PolicyService` was not running (either
the FGS was killed or the service had not yet started). The `adb forward` plumbing is viable
with a live server; the forwarded port is simply dead without it.

**Key constraint:** on a fully-enforcing child device, `adb forward` itself requires adb to be
on. Once `DISALLOW_DEBUGGING_FEATURES` takes effect, `adb devices` may still show the device
but adb commands are rejected at the Android end. A forward established before enforcement fired
may persist but cannot be re-established after.

### 2.2 Options ranked for testing around the adb-dark constraint

| Rank | Option | Mechanism | Assessment |
|---|---|---|---|
| 1 (current) | **e2e-exit-criteria.sh pattern** | Force-stops the watchdog for the adb window; criterion-2 verified out-of-band | Sound and already in use. Demonstrates the restriction blocks adb at OS level. Criterion-2 must be verified via parent UI or log inspection, not automated adb. |
| 2 (best next) | **Injectable `restrictionFilter` seam** | A `(String) -> Boolean` lambda injected at test time; test path omits only `DISALLOW_DEBUGGING_FEATURES`; release passes the identity function (all restrictions in). Mirrors the existing `lock` seam in PolicyEnforcer. | Does not exist yet. Filed as **issue #131**. Clean, auditable, no risk of shipping a weaker build if the seam is constructor-injected rather than a BuildConfig flag. |
| 3 | **Profile-Owner test harness** | Stand up a Work Profile (not DO) for control-plane tests | Exercises policy push / bundle verify but misses the DO enforcement surface. `DISALLOW_*` restrictions in the required set are DO-only and do not apply in a Work Profile context on the primary user. Not useful for this class of test. |
| 4 | **android:testOnly APK flag** | Mark APK test-only to allow install alongside debug runtime | Irrelevant to the adb-dark problem. `android:testOnly` restricts Play distribution but does not affect which DO restrictions are applied at runtime. |

The recommendation is to implement option 2 as follow-up work on issue #131. The seam should be
constructor-injected into `PolicyEnforcer` and the test override excludes
`DISALLOW_DEBUGGING_FEATURES` only. No `BuildConfig.DEBUG` conditional; no weaker build variant
ships.

### 2.3 Two-emulator live-reporting demo without a fully-enforcing child

A non-DO reachable child (BootReceiver to PolicyService to ApiServer, adb alive,
`adb forward tcp:7180 tcp:7180`) gives a working two-emulator live-reporting demo including real
`/usage` reporting, heartbeat, and policy sync. Full-DO enforcement is separately proven by
running the provisioning path on a real device and observing adb go dark. These are two distinct
demonstration modes:

- **Demo mode (emulator, non-DO or DO with `restrictionFilter` seam):** shows the control-plane
  working end-to-end; suitable for developer walkthroughs and CI smoke tests.
- **Enforcement proof (real device, full-DO):** shows adb going dark, demonstrating the actual
  security posture. Can be captured as a video artifact; does not need to be an automated test.

---

## Thread 3 - Cross-OEM Device-Owner Provisioning Matrix

### 3.1 Two new cross-cutting blockers (not in current ADRs)

Before the per-brand table, two findings that affect every brand:

#### (a) Google Play Protect DPC Allowlist (live approximately late 2025) -- issue #133

Google introduced a Play Protect DPC allowlist that gates DO provisioning at OOBE for DPCs not
pre-approved by Google. On devices running the updated Play Protect enforcement, scanning a QR
that points to a non-allowlisted DPC at the "Hi there" OOBE screen results in a provisioning
refusal or a prominent blocker that prevents normal users from proceeding. The mechanism is
documented by Jason Bayton (Sources section 4.1) and confirmed via Android Enterprise community
bug reports as of approximately late 2025.

**Impact on OpenWarden:** ADR-027 D2 documents the Play Store distribution risk but does not
explicitly address the DPC allowlist as a separate, named gate. The allowlist is independent of
Play Store acceptance -- a DPC can be on Play but still not allowlisted for OOBE DO provisioning.
OpenWarden's no-content-monitoring posture is favorable for an approval submission, but approval
is not guaranteed and the timeline is unknown.

**Mitigation:** ADR-027 D2's signed-APK fallback channel is unaffected by Play Protect
allowlisting (the provisioning QR can point to the HTTPS APK URL, and managed-provisioning
verifies the signer pin regardless of source). The APK fallback is therefore the primary
distribution mitigation while allowlist approval is pending, but the allowlist is a hard
prerequisite for the Play-sourced QR-OOBE path.

ADR-027 D2 should be updated to name this as a distinct prerequisite separate from "Play policy risk."

#### (b) Attestation Root Rotation to ECDSA P-384 via Remote Key Provisioning (approximately April 2026) -- issue #134

Google has been rotating device attestation roots from the legacy RSA-2048 Hardware Attestation
roots to new ECDSA P-384 roots via Remote Key Provisioning (RKP). The P-384 roots are the
effective issuing root for new attestation certificates on modern devices enrolled from
approximately April 2026 onward. Jason Bayton and Android Enterprise documentation confirm P-384
certs are being issued on affected devices (Sources section 4.2).

**Impact on OpenWarden:** ADR-029 D6 specifies `oem_roots.json` carrying Google's attestation
roots, but was authored when the primary root was RSA-2048. An `oem_roots.json` that trusts only
the legacy RSA root will fail to verify attestation chains on newly-enrolled devices (Pixels and
Samsung/OnePlus devices provisioned after the rotation date). The result is a fail-closed pairing
refusal -- correct behavior architecturally, but unintended breakage for legitimate devices.

`oem_roots.json` must trust **both** the legacy RSA-2048 Google root and the new ECDSA P-384
Google root. The update path (D6: changes only via a signed parent-app release,
recovery-phrase-gated per ADR-025 D8) is correct and must be executed before v1.0 ships for
Pixel targets.

### 3.2 Per-brand provisioning details

#### Pixel (6+, Android 14+)

| Attribute | Detail |
|---|---|
| **Tier** | Tier 1 -- reference |
| **DO Provisioning** | Clean QR-OOBE via AOSP managed-provisioning. ADB path works without restriction. No OEM-specific OOBE screens to skip. |
| **Key Storage** | Titan M2 StrongBox |
| **Attestation Root** | Google Hardware Attestation RSA-2048 (legacy) **and** Google ECDSA P-384 (RKP, new -- see section 3.1b). Both must be in `oem_roots.json`. |
| **FGS / Watchdog** | No aggressive battery killing. No additional battery exemption needed beyond standard provisioning. |
| **Consumer Friction** | Factory reset required. Otherwise the smoothest OOBE of all surveyed brands. |
| **Rating** | GREEN -- full anti-bypass enforcement, reference platform. |

---

#### Samsung Galaxy S22+ / A55+ / Note (Android 14+)

| Attribute | Detail |
|---|---|
| **Tier** | Tier 2, release-committed (ADR-026 D1) |
| **DO Provisioning** | QR-OOBE works but requires skipping: Samsung Smart Switch migration prompt, Bixby setup, Knox enrollment suggestion, Samsung account creation. ADR-027 D6 specifies these must be handled in the guided QR-OOBE flow. If a Samsung account is created before DO is set, DO provisioning is permanently blocked for that factory-reset instance. |
| **Key Storage** | Samsung Knox Vault (discrete secure element, StrongBox-equivalent) on S22+, Note, **and A55+** -- Knox Vault is present on A55 series (see correction note below). |
| **Attestation Root** | Samsung Knox attestation root (not Google). `oem_roots.json` must include the Samsung Knox root per ADR-029 D1. |
| **FGS / Watchdog** | Samsung Adaptive Battery can kill FGS processes. Battery optimization exemption dialog must be shown during provisioning; provisioning must fail-closed if declined (ADR-027 D6). OEM-preloaded Samsung apps (Galaxy Store, Samsung Internet, Bixby) are FLAG_SYSTEM and can be launched even when the DO allowlist excludes them (ADR-026 D2 Gap 1). Knox-specific DISALLOW_* behavior may conflict on some firmware; bench QA required. |
| **Consumer Friction** | Moderate. OOBE has more vendor screens than Pixel. Parent support questions around absent Bixby and Samsung Pay are expected. The guided OOBE flow (ADR-027 D6) must handle these explicitly. |
| **Rating** | YELLOW -- supported with disclosed gaps per ADR-026 D2. |

**Correction (issue #135):** ADR-026 D2 Gap 3 and ANDROID_COMPAT.md section 3 describe Samsung
A55 as "TEE-only." Samsung Knox Vault is present on the A55 and A55+, making it
StrongBox-equivalent -- not TEE-only. The attestation root is still Samsung Knox (not Google),
so the OEM-root allowlist requirement (ADR-029 D1) stands unchanged. The Gap 3 note in ADR-026
and ANDROID_COMPAT section 3 should be corrected to "Knox Vault (StrongBox-equivalent) on
flagships AND A55+; TEE-only only on budget A-series below A55." This is a doc discrepancy
requiring maintainer + crypto-reviewer confirmation before amending canon ADRs.

---

#### OnePlus 11+ (Android 14+, OxygenOS 14)

| Attribute | Detail |
|---|---|
| **Tier** | Tier 2, release-committed (ADR-026 D1) |
| **DO Provisioning** | Near-AOSP OOBE. QR-OOBE follows standard managed-provisioning with only an OnePlus account screen to skip. ADB path is clean. No Samsung-equivalent OOBE entanglement. |
| **Key Storage** | TEE-level via Snapdragon SPU. No discrete StrongBox equivalent. |
| **Attestation Root** | Google-rooted via RKP at `TRUSTED_ENVIRONMENT` level. `securityLevel == TRUSTED_ENVIRONMENT`. ADR-029 D1 Tier-2 rules apply: TEE accepted, SOFTWARE killed. Both Google RSA-2048 and P-384 roots must be in `oem_roots.json` per section 3.1b. |
| **FGS / Watchdog** | OxygenOS Deep Optimization aggressively kills FGS processes even for apps in the battery-unrestricted exemption list. This is the most aggressive FGS killer among the committed OEMs. The battery exemption grant must be obtained during provisioning and provisioning must fail-closed if not granted (ADR-027 D6). Deep Optimization can re-apply after OTA; post-OTA re-assert (DEFENSES.md row 10) must re-verify. Filed as **issue #136**. |
| **Consumer Friction** | Low OOBE friction compared to Samsung. Primary friction is the battery optimization dialog. |
| **Rating** | YELLOW -- supported with disclosed gaps per ADR-026 D2. |

---

#### Motorola Edge 50+ (Android 14+)

| Attribute | Detail |
|---|---|
| **Tier** | Tier 2, supported but **not release-gating** (ADR-026 D1 footnote) |
| **DO Provisioning** | Near-stock Android OOBE. QR-OOBE and ADB paths are clean; no significant OEM-specific OOBE gates. |
| **Key Storage** | Snapdragon TEE. |
| **Attestation Root** | Google-rooted via RKP at `TRUSTED_ENVIRONMENT` level. Same ADR-029 Tier-2 posture as OnePlus. Both RSA-2048 and P-384 Google roots required. |
| **FGS / Watchdog** | Motorola Battery Care (`com.motorola.batterycare`) kills FGS processes. Consumer remediation via Settings -> Battery -> Battery Care is necessary; the bench-path `adb shell pm uninstall com.motorola.batterycare` is not consumer-viable. The guided provisioning flow must surface the exemption dialog and fail-closed if declined. Motorola's update window is shorter than Samsung/OnePlus (typically 2-3 years); post-OTA regression risk is moderate. |
| **Consumer Friction** | Low OOBE friction. Main friction is Battery Care exemption and shorter-horizon update support. |
| **Rating** | YELLOW (non-gating) -- viable engineering target, not in v1.0 bench/QA matrix. |

---

#### Nothing Phone 2+ (Android 14+, NothingOS)

| Attribute | Detail |
|---|---|
| **Tier** | Tier 2, supported but **not release-gating** (ADR-026 D1 footnote); AMBER caveat |
| **DO Provisioning** | Near-stock Android OOBE (NothingOS is a light Android skin). QR-OOBE and ADB paths are expected to work. However, Nothing Phone 2+ is **not on the Android Enterprise Recommended (AER) list** as of the report date -- DO provisioning is untested against AER criteria and may have undiscovered gaps. |
| **Key Storage** | Snapdragon variant (Nothing Phone 2+): Snapdragon TEE. MediaTek variant (Nothing Phone 2a): MediaTek attestation chain behavior is more variable; bench testing required before any support claim is made for this variant. |
| **Attestation Root** | Snapdragon variant: Google-rooted via RKP, same posture as OnePlus. MediaTek variant: uncertain without device testing. |
| **FGS / Watchdog** | No custom aggressive battery optimization documented for NothingOS. Likely close to stock behavior, but unverified without bench testing. |
| **Consumer Friction** | Low OOBE friction. Primary uncertainty is non-AER status and MediaTek variant behavior. |
| **Rating** | YELLOW/AMBER (non-gating) -- Snapdragon variant tractable; MediaTek variant requires separate investigation. Not in v1.0 bench/QA matrix. |

---

#### Xiaomi (HyperOS / MIUI, any model)

| Attribute | Detail |
|---|---|
| **Tier** | Tier 3 -- not supported, no anti-bypass warranty (ADR-026 D1 / ADR-023) |
| **DO Provisioning** | **Platform-broken as of mid-2026.** HyperOS (successor to MIUI) has documented breakage in the AOSP managed-provisioning flow for DO enrollment via QR-OOBE; the provisioning activity fails to hand off correctly on at least some HyperOS firmware versions. ADB-path `dpm set-device-owner` may succeed on some models but subsequent restriction enforcement is unreliable (Android Enterprise community bug reports). |
| **Key Storage** | Variable by model and firmware. MediaTek-based models add attestation root complexity. Not consistent across the lineup. |
| **Attestation Root** | Variable; no single trustworthy root across the Xiaomi lineup. |
| **FGS / Watchdog** | HyperOS aggressively kills background processes. The MIUI/HyperOS Security app whitelisting is required but does not reliably persist across OTA. |
| **OEM-unlock risk** | `DISALLOW_OEM_UNLOCK` is a **silent no-op** against the Mi Unlock Tool -- Xiaomi's official bootloader unlock tool bypasses the DO-set restriction via Xiaomi's server-side authorization. A motivated kid with physical access and a Xiaomi account can unlock the bootloader despite the DO restriction being set. This is the documented gap in ANDROID_COMPAT.md section 2 / ADR-023 Tier-3 reasoning. |
| **Consumer Friction** | High. OOBE is heavily account-entangled with a Xiaomi account, with aggressive OOBE marketing and anti-enterprise design. |
| **Rating** | RED -- do not attempt. ADR-023 Tier-3 exclusion is correct and this research vindicates it. |

### 3.3 Summary table

| Brand | Tier | QR-OOBE DO | Key Storage | Attestation Root(s) Required | FGS Risk | Rating |
|---|---|---|---|---|---|---|
| Pixel 6+ | Tier 1 | Clean | Titan M2 StrongBox | Google RSA-2048 + P-384 | None | GREEN |
| Samsung S22+ / A55+ / Note | Tier 2 release | Skip Bixby/Smart Switch/Samsung account | Knox Vault (StrongBox; incl. A55 per #135) | Samsung Knox root | Adaptive Battery | YELLOW |
| OnePlus 11+ | Tier 2 release | Skip account screen | Snapdragon TEE | Google RSA-2048 + P-384 (via RKP) | Deep Optimization (HIGH, #136) | YELLOW |
| Motorola Edge 50+ | Tier 2 non-gating | Near-stock | Snapdragon TEE | Google RSA-2048 + P-384 (via RKP) | Battery Care | YELLOW |
| Nothing Phone 2+ | Tier 2 non-gating | Near-stock; non-AER | Snapdragon TEE / MTK uncertain | Google RSA-2048 + P-384 / MTK uncertain | Minimal (unverified) | YELLOW/AMBER |
| Xiaomi HyperOS | Tier 3 | BROKEN | Variable | Variable | Extreme | RED |

### 3.4 Gap-to-green per brand

**Pixel:** implement both Google attestation roots (RSA-2048 + P-384) in `oem_roots.json`
(#134). Add `setUserControlDisabled(self, true)` + `adb_enabled=0` write to the Day-One path
(#132). Otherwise green.

**Samsung:** (1) Correct ADR-026 D2 Gap 3 and ANDROID_COMPAT.md section 3 regarding A55 Knox
Vault (#135 -- maintainer + crypto-reviewer confirmation required before ADR edit). (2) Add
Samsung Knox root to `oem_roots.json` (ADR-029 D6 work). (3) Implement guided OOBE skip for
Smart Switch / Bixby / Samsung account. (4) Implement battery-exemption fail-closed gate
(ADR-027 D6). (5) Bench QA the ATTACKS A-class matrix on Samsung hardware. (6) Disclosure UI
(ADR-026 D3). (7) DPC Play Protect allowlist approval (#133).

**OnePlus:** (1) Implement Deep Optimization exemption fail-closed gate (#136). (2) Verify
both Google roots in `oem_roots.json` (#134). (3) Bench QA on OnePlus hardware. (4) Disclosure
UI (ADR-026 D3). (5) DPC allowlist approval (#133). (6) Post-OTA re-assert bench verification.

**Motorola / Nothing:** non-gating; same pattern as OnePlus with lower priority. Nothing's
MediaTek variant needs a separate investigation before any support claim is made. AER
certification status for Nothing Phone 2+ should be re-checked at v1.0 ship time.

**Xiaomi:** no viable path to green under current architecture without forking Android or
trusting Xiaomi's attestation and OEM-unlock infrastructure. ADR-023 Tier-3 exclusion stands.

### 3.5 Is the "Pixel + Samsung + a couple mainstream" goal realistic?

Yes, under local-only, no-SaaS, QR-OOBE DO constraints. The provisioning architecture is
**fully local** -- factory-reset, scan a QR, Play/APK installs the DPC, DO is set, DISALLOW_*
enforced. The only external dependencies are:

1. **One-time DPC Play Protect allowlist approval** from Google (issue #133 -- required gate,
   not an ongoing runtime dependency; the signed-APK fallback is the mitigation while pending).
2. **HTTPS APK hosting URL** for the signed-APK fallback channel embedded in the QR payload
   (static file serving; no server-side logic, no SaaS, no runtime call home).

Zero-touch enrollment and Samsung Knox Mobile Enrollment are both SaaS-dependent and are
correctly eliminated by the no-SaaS non-negotiable. Neither is needed for the committed device
targets.

Xiaomi is correctly excluded. The managed-provisioning platform breakage and Mi Unlock Tool
bypass are independent of anything OpenWarden can fix without forking Android.

---

## Reconciliation with ADR-026 / ADR-027 / ADR-029

| Finding | ADR / Doc affected | Disposition |
|---|---|---|
| Samsung A55 has Knox Vault (StrongBox-equivalent), not TEE-only | ADR-026 D2 Gap 3, ADR-029 D1, ANDROID_COMPAT.md section 3 | Discrepancy. Filed #135. Requires maintainer + crypto-reviewer confirmation before amending canon ADRs. This research alone is not sufficient to update those docs. |
| Google attestation root rotation to ECDSA P-384 (RKP); `oem_roots.json` must trust both roots | ADR-029 D6 | Gap in current ADR text. Filed #134. D6 update needed at implementation time. |
| Play Protect DPC allowlist is a distinct provisioning gate, separate from Play Store acceptance | ADR-027 D2 | Gap: D2 names "Play policy risk" but not the allowlist as a named prerequisite. Filed #133. D2 should be updated to name this explicitly. |
| `setUserControlDisabled` + `adb_enabled=0` write documented in DEFENSES.md / ATTACKS.md but not implemented | DEFENSES.md row 1, ATTACKS.md line 134 | Implementation gap (not ADR gap). Filed #132. Existing docs are correct; implementation is incomplete. No ADR change needed. |
| Injectable `restrictionFilter` seam needed for testing without weakening release | Testing gap | Filed #131. Implementation work, not a docs/ADR change. |
| OnePlus Deep Optimization FGS killing not captured as a named per-OEM risk | ADR-026 D2 Gap 4, ANDROID_COMPAT.md section 7 | Gap in current ADR text. Filed #136. A note in ADR-026 D2 or ANDROID_COMPAT section 7 is needed. |

**Note on amending ADRs:** findings #133, #134, #135, #136 each involve a potential change to
Accepted ADRs (ADR-026, ADR-027, ADR-029). ADR amendments touching crypto or pairing are
agent-blocked (human + crypto-reviewer sign-off required). This research surfaces the findings;
it does not itself amend any ADR. The issues filed are the correct escalation path.

---

## Sources

### 1. Android Developer Documentation (AOSP)
- `UserManager.DISALLOW_DEBUGGING_FEATURES` javadoc -- "Specifies if a user is disallowed
  from enabling or accessing debugging features." Notes `ADB_ENABLED` is set to 0 by the
  platform when this restriction is active.
- `UserManager.DISALLOW_APPS_CONTROL` javadoc -- load-bearing quote: "...the user will still
  be able to perform those actions via other means (such as adb)." Establishes that this
  restriction does not close the adb console.
- `UserManager.DISALLOW_USB_FILE_TRANSFER` javadoc -- covers MTP; does not mention ADB protocol.
- `UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` javadoc -- covers UI consent for
  unknown-source installs; `adb install` is not affected.
- `DevicePolicyManager.setUserControlDisabled` (API 30+) -- prevents the user from stopping,
  force-stopping, or clearing data for the calling admin's own package.
- Android Enterprise QR provisioning overview:
  https://developers.google.com/android/work/requirements/provisioning
- Remote Key Provisioning (RKP) overview:
  https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/master/security/rkp/README.md

### 2. Samsung Knox
- Knox Vault overview and supported device list -- Samsung Knox developer portal
  (https://docs.samsungknox.com/). Source for the Knox Vault / A55 correction (issue #135).
- Samsung Knox Key Attestation root certificate documentation.

### 3. In-repo Sources (verified directly for this report)
- `child-android/app/src/main/kotlin/com/openwarden/child/PolicyEnforcer.kt` -- lines 322-346
  (`requiredRestrictionsForSdk`); line 326 (`DISALLOW_DEBUGGING_FEATURES`). Verified directly:
  `setUserControlDisabled` and `adb_enabled` write are absent from main source.
- `docs/ATTACKS.md` -- K1/K2 attack ratings; line 134 (`ADB_ENABLED=0` documented as defense).
- `docs/DEFENSES.md` -- row 1 (`setUserControlDisabled(self, true)` documented as shipped).
- `docs/adr/020-failclosed-dayone-restrictions.md` -- D1 fail-closed non-negotiable.
- `docs/adr/026-top-oem-release-scope.md` -- D1 (device targets), D2 (disclosed gaps including
  Gap 3 TEE-only Samsung assumption now under review per #135), D3 (disclosure gate), D5
  (prerequisites list).
- `docs/adr/027-provisioning-distribution-model.md` -- D2 (Play distribution + APK fallback),
  D6 (per-OEM OOBE handling + battery-exemption fail-closed gate).
- `docs/adr/029-tier2-attestation-posture.md` -- D1 (per-tier attestation acceptance table),
  D6 (`oem_roots.json` update path; dual-root gap identified here as issue #134).

### 4. Jason Bayton -- Android Enterprise Research
- **4.1** "DPC allowlist for Android Enterprise" -- documents the Google Play Protect DPC
  allowlist mechanism, approval process, and impact on non-allowlisted DPCs at OOBE:
  https://bayton.org/android/android-enterprise-dpc-allowlist/
- **4.2** "Google's attestation key rotation" -- documents the RSA-2048 to ECDSA P-384
  transition via RKP and the effective enrollment date (approximately April 2026):
  https://bayton.org/android/android-attestation-key-rotation/

### 5. Don't Kill My App -- Per-OEM FGS Behavior
- OnePlus (OxygenOS) Deep Optimization: https://dontkillmyapp.com/oneplus
- Motorola Battery Care: https://dontkillmyapp.com/motorola
- Samsung Adaptive Battery: https://dontkillmyapp.com/samsung

### 6. Android Enterprise Community Bug Reports
- Xiaomi HyperOS DO provisioning breakage -- multiple reports in the Android Enterprise Help
  Community forum (approximately mid-2026) documenting `com.android.managedprovisioning`
  failures on HyperOS firmware variants.
- Google Play Protect DPC allowlist enforcement -- Android Enterprise community discussion of
  provisioning refusals for non-allowlisted DPCs on updated Play Protect versions
  (approximately late 2025).
