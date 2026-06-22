# OpenWarden — Broad Android Compatibility Research

> **Status:** v2+ scoping note. Directly relitigates [`SIMPLIFY.md`](openwarden/docs/SIMPLIFY.md) §3 *"One target device family — Pixel 7/7a/7 Pro for v1."* This document is the cost-and-shape sketch for what "works on any modern Android" would require, so the maintainers can decide whether to spend the budget. It does not change v1 scope.
> **Companion docs:** [`ATTACKS.md`](openwarden/docs/ATTACKS.md), [`DEFENSES.md`](openwarden/docs/DEFENSES.md), [`CRYPTO.md`](openwarden/docs/CRYPTO.md), [`PROVISIONING_V2.md`](openwarden/docs/PROVISIONING_V2.md), [`SIMPLIFY.md`](openwarden/docs/SIMPLIFY.md).
>
> **Superseded for current scope by [ADR-023](adr/023-enforcement-floor-tiers.md).** That ADR is the
> **canonical, committed enforcement-tier definition.** Everything in *this* doc — the broader Tier-2
> device list below (incl. Xiaomi / Sony / ASUS / Fairphone), the per-OEM tables, the sample parent
> /marketing copy ("works on … major brands"), and the proposed "Tier 2 (major OEMs)" rule — is
> **v2+ aspirational scoping, NOT a current support or marketing claim.** For any present-day tier
> classification or parent-facing wording, use ADR-023's **model-scoped** list: **Tier 2 = Samsung
> Galaxy S22+/A55+/Note, OnePlus 11+, Motorola Edge 50+, Nothing Phone 2+**; everything else —
> including Xiaomi and untested models of those brands — is **Tier 3** best-effort.
>
> **Update — ADR-026 / ADR-027 (Proposed, 2026-06-17):** §3 (StrongBox→TEE fallback), §4 (per-OEM
> attestation roots), §5 (FGS/battery-saver), and §7 (per-OEM provisioning) are **lifted into v1.0
> release-prerequisite scope** for the committed Pixel + Samsung + OnePlus set — they are now v1
> engineering inputs, not only v2+ aspiration. The rest of this doc (the broader OEM list, marketing
> copy) remains v2+ scoping superseded by ADR-023. This note becomes the authoritative scope once
> ADR-026/027 flip to Accepted (with the Blocked-on ADR-025 amendment).

The user's ask: keep Pixel as the best-supported target, but stop refusing non-Pixel Androids. The mechanical reason OpenWarden said no until now is that every defense in `DEFENSES.md` and every assumption in `CRYPTO.md` §3/§10 is rooted in Google's hardware attestation chain + Titan M2 StrongBox + a fully predictable AOSP DPC surface. None of those generalize cleanly. They generalize *with caveats*, and the caveats are exactly what this doc enumerates.

---

## 1. Tier proposal

Three tiers, with explicit "what works, what's weaker, what's broken" for each. The labels go on the parent app device-picker (§ "Doc impacts") and on the GitHub README support matrix.

### Tier 1 — Full support (Pixel 6+, Android 14+ stock)

**Works:**
- Every `DISALLOW_*` from `DEFENSES.md` row #2 — verified on bench Pixel.
- StrongBox-backed EC P-256 device-binding key with Google-rooted attestation chain, binding the TEE-resident Ed25519 + X25519 identity keys (`CRYPTO.md` §3, [ADR-032](adr/032-child-identity-hardware-binding-strongbox-p256.md); StrongBox cannot hold Curve25519).
- Verified Boot GREEN as a hard requirement (`CRYPTO.md` §10).
- Real FRP bound to a Google account; `fastboot -w` produces a brick recoverable only via FRP account or BIP39 phrase (`PROVISIONING_V2.md` §6).
- FGS watchdog behaves predictably on stock — Pixel respects `setExactAndAllowWhileIdle` without the OEM-specific battery-shenanigans that plague other vendors.
- Atomic provisioning via the Desktop GUI Provisioner (`PROVISIONING_V2.md` §5).

**Weaker:** Nothing — this is the reference platform.

**Broken:** Nothing.

### Tier 2 — Works with documented caveats

*(v2+ aspiration — superseded for current scope by ADR-023; the committed Tier 2 is only Samsung Galaxy S22+/A55+/Note, OnePlus 11+, Motorola Edge 50+, Nothing Phone 2+. The rest below are Tier 3 today.)*
Samsung Galaxy S22+ / Galaxy A55+ (2024-on) / Note series; OnePlus 11+; Xiaomi 13+/14+/HyperOS flagships; Sony Xperia 1 V+; Nothing Phone 2+; Motorola Edge 50+; ASUS ROG Phone 8+; Fairphone 5+.

**Works:**
- AOSP DPC APIs — `dpm set-device-owner` works on stock OOBE if no Google account is added (per AOSP `ManagedProvisioning` contract; each OEM ships a compliant build to pass CTS).
- The structural `DISALLOW_*` set (FACTORY_RESET, SAFE_BOOT, DEBUGGING_FEATURES, MODIFY_ACCOUNTS, ADD_USER, USER_SWITCH, CONFIG_DATE_TIME) — these are mandatory CTS-tested.
- Hardware-backed keystore (TEE — every Android 13+ device has at minimum a TEE Keymaster).
- Verified Boot is on by default on all CTS-passing OEMs since Android 9, though the root key is OEM-specific (Samsung's, Xiaomi's, etc., not Google's).
- FRP exists, though the binding is to whichever account ecosystem the OEM uses for sign-in recovery — usually Google, but Xiaomi additionally pulls in Mi Account, Honor pulls in Huawei/Honor ID.

**Weaker:**
- Attestation root is not Google's — parent must allowlist the OEM root (Samsung Knox attestation root, Xiaomi attestation root, etc.). See §4.
- StrongBox: present only on Samsung Galaxy S21+ flagships (via Knox Vault), some Sony flagships, some Xiaomi flagships. A-series, OnePlus, Motorola, Nothing, Fairphone are TEE-only. See §3.
- FGS lifetime is OEM-dependent. Xiaomi MIUI/HyperOS will kill an FGS within minutes of screen-off unless the user explicitly whitelists the app from battery optimization. Samsung One UI is moderate. See §5.
- Bootloader unlock policy is mixed — Pixel unlock is one toggle in Dev Options; Samsung is locked permanently in many regions (US carrier variants); Xiaomi requires a 7-day waiting period + Mi Account login; OnePlus is straightforward; Motorola requires an OEM-issued code; Fairphone openly supports unlock.

**Broken:**
- `DISALLOW_OEM_UNLOCK` is honored on devices where the bootloader unlock surface lives in Dev Options; on Xiaomi (which uses the Mi Unlock Tool, a separate Windows binary) the restriction is a silent no-op against the only realistic kid attack path.
- Smart Switch / Bixby / Xiaomi-cloud restore can re-introduce a managed account post-provisioning if not blocked at OOBE. Provisioning script must skip those screens explicitly.
- Per-OEM "smart power saving" modes can override DPC battery-optimization opt-out. Documented but unfixable from app code.

### Tier 3 — Best-effort (any Android 13+ with DPC support)

Anything that exposes the AOSP DPC API and passes CTS but isn't on the explicit Tier 2 list — e.g. Tecno, Infinix, Realme China-only ROMs, Lenovo tablets, ZTE, Vivo.

**Works:**
- AOSP DPC core API will function or the device wouldn't have shipped Android.
- Signed policy bundles, sealed-box envelopes, BIP39 recovery — these are all cryptographic and OEM-agnostic.

**Weaker:**
- No hardware attestation verification — parent app sets `OPENWARDEN_ATTESTATION_MODE=unverified` and warns loudly during pairing.
- TEE-only key storage assumed; no StrongBox check.
- FGS hostility is unknown; parent must validate by hand for the first week.

**Broken:**
- No support guarantee. OpenWarden prints "best effort — please file an issue with your device's model + Android build fingerprint" during pairing. Issue gets `tier3-device` label and is community-supported.

---

## 2. Per-OEM DPC quirks

Sourced from AOSP CTS reports, [DontKillMyApp.com](https://dontkillmyapp.com), Headwind MDM's device matrix, XDA threads, and the Android Enterprise Recommended program list.

| OEM | `dpm set-device-owner` on stock OOBE | OEM provisioning hook | `DISALLOW_*` honored | FRP binding | Verified Boot root | Battery / FGS | Bootloader unlock |
|---|---|---|---|---|---|---|---|
| **Pixel (Google)** | Yes, clean. | None — pure AOSP. | All. | Google account. | Google. | Stock — kind to FGS. | Toggle in Dev Options. |
| **Samsung** | Yes, but skip Samsung-account + Smart Switch + Bixby screens. KME (Knox Mobile Enrollment) competes; ignore — see §6. | KME, Knox Vault binding. | All AOSP; plus extra Knox restrictions we don't use. | Google + Samsung account dual-bind in some regions (Korea). | Samsung Knox root. | Adaptive Battery on by default; will kill un-whitelisted FGS overnight. Improving in One UI 7. | Locked permanently on US carrier variants; toggle on international + EU. |
| **Xiaomi (MIUI / HyperOS)** | Yes on Global ROM stock; **China ROM frequently breaks DPC** by pre-pulling a Mi Account. Force Global ROM in onboarding. | Mi Account push at OOBE. | Most; `DISALLOW_OEM_UNLOCK` is a no-op vs Mi Unlock Tool. | Google + Mi Account dual-bind. | Xiaomi root. | **The worst.** HyperOS aggressively kills FGS unless app is "Autostart" + "No battery restrictions" + locked-in-recents. Requires explicit parent walkthrough during onboarding. | 7-day wait + Mi Account sign-in + Mi Unlock Tool on Windows. |
| **OnePlus (OxygenOS post-merger ColorOS)** | Yes on stock. | None major. | All AOSP. | Google account. | OnePlus root. | Aggressive — inherits ColorOS battery management. Whitelist required. | Toggle in Dev Options (simpler than most). |
| **Motorola** | Yes on stock. | None — closest to AOSP among non-Pixel. | All AOSP. | Google account. | Motorola root. | Closer to stock; kinder than Xiaomi/OnePlus. | OEM-issued unlock code required. |
| **Oppo (ColorOS)** | Yes. | Heytap account push. | All AOSP. | Google + Heytap. | Oppo root. | Aggressive. Same playbook as Xiaomi. | Not officially supported in most markets. |
| **Honor (post-Huawei)** | Yes on Magic OS 8+. | Honor ID push. | All AOSP. | Google + Honor ID. | Honor root. | Aggressive. | Limited; varies by model + region. |
| **Realme** | Yes on Global. | Realme account push. | All AOSP. | Google + Realme account. | Realme root. | Aggressive (ColorOS base). | OEM tool required. |
| **Nothing (NothingOS)** | Yes on stock — very close to AOSP. | None. | All AOSP. | Google account. | Nothing root. | Close to stock; gentle on FGS. | Toggle in Dev Options. |
| **Fairphone** | Yes on stock. | None. | All AOSP. | Google account (also supports /e/OS which has its own). | Fairphone root (or /e/OS root). | Stock-like. | Officially supported, simple. |

The trend: **OEMs from "Western / EU markets" (Motorola, Nothing, Fairphone) hew close to AOSP and "just work."** Chinese-origin OEMs (Xiaomi, Oppo, Honor, Realme) layer an account ecosystem on top that complicates provisioning and aggressively manages background work. Samsung sits in the middle — clean DPC, but extra OOBE screens to skip and an attestation root that isn't Google's.

---

## 3. StrongBox availability matrix

> **Monotonic counter note (ADR-017):** There is **no app-usable rollback-resistant monotonic counter on any Android tier** — not Tier 1 (Pixel 6+ / Titan M2), not Tier 2 (Samsung Knox Vault, Snapdragon SPU), not Tier 3. Android's `KeyGenParameterSpec.Builder` exposes `setIsStrongBoxBacked(boolean)` and `setMaxUsageCount(int)` (a usage countdown toward zero, not an app-readable/-settable monotonic counter), and nothing else resembling a rollback-resistant counter. KeyMint/Keymaster HAL has `Tag::ROLLBACK_RESISTANCE`, but that governs whether the *key blob* survives a factory reset — it is not surfaced to apps as a readable/writable counter. **StrongBox vs TEE affects only at-rest key isolation (confidentiality and integrity of stored keys), not monotonicity of any app-held value.** The `policy_seq` replay floor is therefore best-effort at-rest + chain-mirrored, with the parent device as the authoritative monotonicity anchor (see [`CRYPTO.md`](CRYPTO.md) §5, §8 and [`PROTOCOL.md`](PROTOCOL.md) §5). The parent app MUST NOT advertise "hardware rollback resistance" on any tier.

`CRYPTO.md` §3 is currently absolutist: *"`setIsStrongBoxBacked(true)` is non-negotiable."* That has to relax for any Tier 2 support.

### Devices with hardware StrongBox in 2026

- **Pixel 6+ (Titan M2 chip)** — Google's hardware security module. The reference.
- **Samsung Galaxy S21+ flagships (Knox Vault)** — Samsung's dedicated secure element. CTS-attested StrongBox, but the cert chain is rooted in Samsung Knox.
- **Sony Xperia 1 V / 1 VI / 5 V** — Sony's secure element (Snapdragon QSEE + dedicated SE).
- **Some Xiaomi flagships (13 Pro / 14 / 14 Pro / Ultra)** — Snapdragon SPU. Cert chain rooted in Xiaomi.
- **OnePlus 12 / 13** — same Snapdragon SPU pattern as Xiaomi flagships, intermittently.
- **iQOO 12 / Vivo X100 Pro** — Snapdragon SPU, intermittently.

### Devices on TEE only (no StrongBox)

- **All Pixel A-series Pixel 5a and earlier** (Pixel 6a onward have Titan M2).
- **All Samsung A-series** — even A55 (2024).
- **All OnePlus Nord / OnePlus 10 and earlier**.
- **Most Motorola** — including Edge 50.
- **All Nothing phones to date** through Phone 2a.
- **Fairphone 4 and 5**.
- **All sub-flagship Xiaomi (Redmi, Poco below the K series)**.

### Market share estimate

StrongBox shipped on roughly **15–20% of new Android devices sold globally in 2024**, dominated by Pixel + Samsung flagships + a slice of Snapdragon-flagship Chinese OEMs. The other 80%+ — A-series, Redmi, Nord, Edge, Nothing, Fairphone — are TEE-only. Source extrapolated from Counterpoint Research handset-mix data + Samsung Knox Vault model list + Google Titan M2 model list.

### What's lost on TEE-only

- **Hardware key isolation still present.** TEE Keymaster keeps the key out of user-space RAM and out of the kernel. A rooted kid cannot `cat` the privkey.
- **Tamper resistance to physical attack is reduced.** A discrete SE survives EM-glitching and side-channel attacks better than TEE-on-SoC. For our threat model (`ATTACKS.md` adversary: kid with YouTube + adb, no JTAG, no oscilloscope), **TEE is acceptable.**
- **Attestation still works** — TEE Keymaster attests; the cert chain just terminates at the OEM's TEE root rather than Google's hardware root.

### CRYPTO.md fallback rule (proposed)

Update `CRYPTO.md` §3 to:

1. **Tier 1 (Pixel):** require StrongBox. Refuse to provision if `setIsStrongBoxBacked(true)` throws.
2. **Tier 2 (Samsung Knox Vault, Sony, flagship Snapdragon):** require StrongBox per the [official Android StrongBox-capable device list](https://source.android.com/docs/security/features/keystore); attestation root must match the per-OEM allowlist (§4).
3. **Tier 3 (everything else):** fall back to TEE-only with `setIsStrongBoxBacked(false)`. Surface a "TEE-only — physical-attack resistance reduced" warning in the parent pairing UI. Do not fail provisioning. Pin the TEE attestation cert chain.

The threat model already excludes nation-state and JTAG attackers (`ATTACKS.md` §1). TEE-only is consistent with stated threat model.

---

## 4. Attestation root variance

`CRYPTO.md` §10 currently requires *"Cert chain root: one of Google's hardware attestation roots."* That's Pixel-only by design. For broader support we need an allowlist of OEM roots.

**Pre-load in `:shared:crypto`:**

| Root | Source | Covers |
|---|---|---|
| Google hardware attestation roots | [list](https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate) | All Pixel 6+ |
| Samsung Knox attestation roots | Samsung Knox Whitepaper / Knox Attestation API docs | S21+, recent A-series, Note |
| Sony attestation root | Sony Developer site | Xperia 1 V+ |
| Xiaomi attestation root | Xiaomi MIUI security docs (less well-documented) | flagship Mi/Redmi K |
| OnePlus attestation root | shared OnePlus/Oppo cert authority | OnePlus 11+ |
| Motorola attestation root | Motorola Mobility cert authority | Edge series |

**Parent-side update path:** the `:shared:crypto` module ships with a `oem_roots.json` resource. **(Superseded by ADR-029 D6: the "refresh via the signed-bundle channel" idea is REJECTED — `oem_roots.json` and the model allow-list change *only* via a signed parent-app release, never the runtime bundle channel, AND a root/model addition is recovery-phrase-gated per ADR-025 D8. A runtime data-push of trusted roots would let a compromised day-to-day signing key widen trust without the recovery phrase.)** Adding a new OEM root is a parent-app update, not a child-app update — the parent does verification, the child just produces the cert chain.

**Unknown OEMs:** if the leaf cert chains to a root not in the allowlist, parent app surfaces "Unverified attestation — proceed only if you trust this device" with a one-tap dismissal and an `unverified_attestation=true` flag stored in the pairing record. The flag never expires; the parent always knows which child phones have unverified attestation. This matches Tier 3.

---

## 5. FGS / battery-saver hostility by OEM

DontKillMyApp.com's per-OEM kill-rate data plus our own bench testing on Tier 2 devices feeds the table below. The key product question: *"How likely is it that the kid's OpenWarden app will be silently killed by the OEM's battery saver overnight?"*

| OEM | Kill aggression | Mitigation |
|---|---|---|
| **Xiaomi MIUI / HyperOS** | Severe. FGS killed within 30 minutes of screen-off if not whitelisted across **three** separate settings (Autostart, Battery Saver, lock app in recents). | Provisioning script walks parent through all three. Refuse to mark provisioning complete until the three are set (verifiable via `dumpsys deviceidle` and `appops`). |
| **OnePlus OxygenOS / Oppo ColorOS** | High. Similar to Xiaomi — multiple toggles required. | Same playbook. |
| **Honor MagicOS / Realme RealmeUI** | High. ColorOS-derived. | Same playbook. |
| **Samsung One UI** | Moderate. Adaptive Battery on by default kills FGS after ~3 days of low usage. Improving in One UI 7. | Single toggle: "Never sleeping apps" — add OpenWarden. Provisioning script automates the prompt via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. |
| **Motorola** | Low. Close to stock. | Standard `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt. |
| **Nothing** | Low. | Standard. |
| **Fairphone** | Low. | Standard. |
| **Sony** | Low–moderate. | Standard. |
| **Pixel** | Lowest. The reference. | Standard. |

**Defenses:**

1. **Aggressive watchdog AlarmManager re-arm** (already in `DEFENSES.md` #10, expand for OEM context): on Tier 2 + Tier 3 devices, set a `setExactAndAllowWhileIdle` alarm every 60 seconds rather than the default 5 minutes. Trades battery for liveness.
2. **"Don't optimize OpenWarden" prompt at provisioning** — required step. The provisioning script does not transition to S9 until `PowerManager.isIgnoringBatteryOptimizations(self) == true`.
3. **Refuse to operate if battery saver mode repeatedly kills the service.** Detection: if the watchdog re-arms more than 5 times in 24h, send a `tamper.fgs_killed_repeatedly` sealed event to the parent and enter strict baseline (lock-task to OpenWarden-only). Parent sees the alert and can advise the kid to fix the toggle — or, more likely, fix it themselves.
4. **Heartbeat silence alarms** (`DEFENSES.md` #8) already covers the parent-side detection vector. The watchdog covers the child-side recovery vector. Both belt and suspenders.

---

## 6. OEM-specific tools to embrace (or skip)

**Samsung Knox SDK / Knox Mobile Enrollment (KME):** rich proprietary API surface. Lets you do things AOSP DPC can't (per-app SELinux contexts, deep VPN integration). **Skip in v2.** Reason: violates the Apache-2.0-FOSS posture; ties us to Samsung; doubles the test surface; the `SIMPLIFY.md` 5-year test fails outright. If a Samsung-only fork wants to take advantage, the Apache license is the answer.

**Xiaomi Mi Account binding:** entangles with FRP. Setting up OpenWarden provisioning on a Mi Account-bound device is harder; rotating the Mi Account post-provisioning is harder still. **Avoid integration entirely.** The provisioning script's job is to ensure Mi Account is *not* added before `dpm set-device-owner` runs. Document loudly: "On Xiaomi, do not sign into a Mi Account during OOBE."

**Huawei HMS Core (Honor):** post-2024 Honor still ships HMS in China-region builds. Same posture: avoid the proprietary surface, stick to AOSP DPC.

**Recommendation:** **Stick to AOSP DPC APIs everywhere.** Any feature that requires an OEM SDK is by definition a feature we cannot ship for all OEMs and is therefore a `SIMPLIFY.md` Tier 3 candidate — not in core.

---

## 7. Provisioning across OEMs

OOBE varies dramatically. The `PROVISIONING_V2.md` state machine assumes Pixel OOBE: "Hi there → Wi-Fi → Set up offline → done." On non-Pixel devices there are extra screens to skip and OEM-specific pitfalls.

**Per-OEM OOBE notes:**

- **Pixel:** clean. Reference OOBE.
- **Samsung:** "Hi there" → Wi-Fi → "Bring your data?" (Smart Switch — **skip**) → Google sign-in (skip) → Samsung account (skip) → Bixby (skip) → Knox setup prompt (skip — DPC will take over) → privacy/legal → done.
- **Xiaomi (Global ROM):** Wi-Fi → Google sign-in (skip) → Mi Account (**critical: skip; tap "Skip" in the corner, not "Sign in"**) → Mi Cloud (skip) → ads/MIUI personalization (decline all toggles) → done. China ROM is unsupported.
- **OnePlus:** clean — similar to Pixel after Google sign-in.
- **Motorola:** clean.
- **Oppo / Honor / Realme:** similar pattern to Xiaomi (extra account push + recommended-app installs to decline).
- **Nothing / Fairphone:** clean, near-AOSP.
- **Sony:** clean.

**Per-OEM provisioning scripts in `provisioner/` directory:**

```
provisioner/
  provision.sh                 # core script — OEM-agnostic S0–S10 logic
  oem/
    pixel.sh                   # no-op pre-hooks; the reference
    samsung.sh                 # skip Smart Switch / Bixby / Knox OOBE screens
    xiaomi.sh                  # force-skip Mi Account; verify Global ROM
    oneplus.sh                 # standard, with battery-optimization toggle automation
    motorola.sh                # standard
    nothing.sh                 # standard
    sony.sh                    # standard
    generic.sh                 # Tier 3 fallback; emits warnings
```

Each OEM script exports a few hooks (`pre_oobe`, `post_oobe`, `oem_battery_optimization_steps`, `oem_attestation_root_hint`) consumed by the core script. The core flow stays single-sourced.

The Desktop GUI Provisioner (`PROVISIONING_V2.md` §5) auto-detects the device via `getprop ro.product.manufacturer` and `ro.product.model` and routes to the right OEM script. Detection failure → `generic.sh` + Tier 3 warning.

---

## 8. Test matrix recommendation for v1.x → v2

Bench device acquisition, ranked by ROI:

| Priority | Device | Approx 2026 used cost | Why |
|---|---|---|---|
| **Required** | Pixel 7a | ~$200 | Tier 1 reference. Already in `PROVISIONING_V2.md` §10 plan. |
| **Required for v2** | Samsung Galaxy A55 (2024) | ~$280 used | Largest Tier 2 user population. Samsung global market share + A-series being the volume seller. |
| **Strongly recommended** | OnePlus 12 or Motorola Edge 50 | ~$300 used | Snapdragon SPU StrongBox (OnePlus 12) or Motorola-stock baseline. Pick one. |
| **Nice-to-have** | Xiaomi 14 (Global ROM) | ~$350 used | Worst-case battery hostility + Mi Account complexity. Exercising the OEM script that's most likely to break. |
| **Optional** | Nothing Phone 2 or Fairphone 5 | ~$300 used | Validates the "near-AOSP" assumption and the FOSS-friendly OEM positioning. |

**Test plan per device** (extending `PROVISIONING_V2.md` §10):

1. Full `provisioning/provision.sh` against fresh OOBE.
2. The A-class attack sweep from `ATTACKS.md` (Safe Mode, factory reset, ADB dpm, sideload, etc.) — verify each is blocked.
3. 7-day uptime test with the OEM's default battery saver enabled. The Xiaomi run will fail; document the failure mode and the required toggles.
4. OTA test if an update is available during the window.
5. Pairing + attestation verification — confirm cert chain validates against the OEM root.
6. FRP behavior: attempt `fastboot -w` (where possible), verify FRP screen appears, verify which account it demands.

A v2 release that ships "broad Android" without (1) and (2) on at minimum the Pixel + Samsung A55 + one other OEM is not a v2 release. The emulator does not validate per-OEM quirks.

---

## 9. Marketing positioning

The README and parent-onboarding copy should be honest about the tier difference. Proposed phrasing:

- **"Best on Pixel."** Full feature support. All defenses work. Hardware attestation against Google root. StrongBox-backed keys. The reference platform.
- **"Works on Samsung, OnePlus, Xiaomi, Motorola and other major brands — with documented caveats."** Most defenses work; attestation rooted in OEM cert authority; battery-saver setup required during provisioning; specific OEM provisioning script per device. The parent will see a banner during pairing summarizing what's weaker on their device.
- **"Other Androids — DIY, best-effort."** Any Android 13+ with DPC. Community-supported. No attestation verification. TEE-only keystore assumed. Provided as a documented escape hatch, not a recommended path.

Set the parent's expectations *at the device-picker step* of onboarding (`ONBOARDING.md` impacts, §10 below), not in fine print. Honest tiering builds trust; hiding the asymmetry creates "the app doesn't work" reviews.

---

## 10. Doc impacts

Changes needed to make this real:

- **`ARCHITECTURE.md`** (does not yet exist as a top-level doc): introduce the device-tier system as a first-class concept. Tier 1/2/3 referenced from every other doc.
- **`CRYPTO.md` §3, §10:** add the StrongBox-or-TEE fallback rule. Per-OEM attestation root allowlist. Update test vectors to include one Samsung Knox attestation chain and one TEE-only chain in `attestation_chain.json`.
- **`DEFENSES.md` row #4:** loosen "StrongBox" to "hardware-backed keystore — StrongBox preferred." Row #9 (AVB check) needs a per-tier rule: GREEN required on Tier 1+2, warn-only on Tier 3.
- **`PROVISIONING_V2.md` §2, §4, §5, §7:** per-OEM script subdirectory documented; emulator path still primary for dev; OEM script test matrix added to §10.
- **`ONBOARDING.md`:** add a device-tier picker as the first screen of pairing. Show the tier badge plus the "what's weaker" summary. Parent acknowledges before proceeding.
- **`README.md`:** device support matrix. Pixel green checkmark, Samsung/OnePlus/Xiaomi/Motorola yellow checkmark with link to per-OEM doc, others gray "best-effort."
- **`SIMPLIFY.md` §3:** the "one target device family" rule needs an ADR-level edit. The rule is not wrong — it bought us focused v1 delivery. It is just outdated for v2+. The replacement rule: *"One target tier — Tier 1 (Pixel) for v1. Tier 2 (major OEMs) for v2. Tier 3 is community-supported, never a core deliverable."* Tiering preserves the spirit (we will not chase every OEM bug) while opening the door.
- **`ATTACKS.md`:** add OEM-specific attack rows where they differ — e.g., "Xiaomi battery-saver kills FGS overnight" (mitigated by §5 above), "Samsung Smart Switch restore re-adds a Google account" (mitigated by OOBE-script discipline).
- **`ROADMAP.md`:** schedule Tier 2 as a dedicated v2.0 epic, not a slow drift across v1.x point releases.

---

## 11. References

- [AOSP Compatibility Test Suite (CTS)](https://source.android.com/docs/compatibility/cts)
- [AOSP Managed Provisioning](https://source.android.com/docs/devices/admin/managed-provisioning)
- [Android Key Attestation reference + Google root cert list](https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate)
- [Android StrongBox-capable device list (AOSP source.android.com)](https://source.android.com/docs/security/features/keystore)
- [Samsung Knox Vault whitepaper](https://docs.samsungknox.com/admin/whitepaper/knox-platform/secure-boot.htm)
- [Samsung Knox Mobile Enrollment (KME)](https://www.samsungknox.com/en/solutions/it-solutions/knox-mobile-enrollment)
- [DontKillMyApp.com — per-OEM background-killing data](https://dontkillmyapp.com/)
- [Headwind MDM open-source DPC device matrix](https://h-mdm.com/)
- [Android Enterprise Recommended program list](https://androidenterprisepartners.withgoogle.com/devices/)
- [Xiaomi Mi Unlock Tool docs](https://en.miui.com/unlock/) (informational — we do not integrate)
- XDA Developers forums — Pixel, Samsung, OnePlus, Xiaomi, Motorola provisioning threads (per-device specifics)
- Companion OpenWarden docs: [`ATTACKS.md`](openwarden/docs/ATTACKS.md), [`DEFENSES.md`](openwarden/docs/DEFENSES.md), [`CRYPTO.md`](openwarden/docs/CRYPTO.md), [`PROVISIONING_V2.md`](openwarden/docs/PROVISIONING_V2.md), [`SIMPLIFY.md`](openwarden/docs/SIMPLIFY.md)

---

## Bottom line

Broad Android support is technically achievable and the OEM landscape doesn't make it as terrible as it could be — Motorola, Nothing, Fairphone, and Sony are near-AOSP; Samsung is well-behaved with extra OOBE friction; OnePlus is acceptable. Xiaomi is the worst-case for battery-saver and account entanglement but solvable with a forceful provisioning script. The crypto story relaxes cleanly from "StrongBox required" to a tiered "StrongBox where available, TEE elsewhere, all with attestation against an OEM-root allowlist." The biggest *organizational* cost is not the code — it's the bench-test matrix and the ongoing per-OEM bug triage. That cost is the reason `SIMPLIFY.md` §3 said no in v1, and it's a real cost that needs grant runway to absorb in v2.

Recommendation: do this in v2.0 as a single epic with the three-bench-device test matrix, not as drift across v1.x. Land Tier 1 stable; commit to Tier 2 as an explicit version bump with explicit scope; declare Tier 3 community-supported and stop apologizing for it.
