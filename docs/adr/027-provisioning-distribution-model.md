# ADR-027: Provisioning + distribution model — QR-OOBE Device-Owner is the primary consumer path; Play-distributed; ADB stays the power/bench path; the weak no-DO mode is rejected (amends PROVISIONING_V2)

Status: Accepted
Date: 2026-06-17
Relates: docs/PROVISIONING_V2.md (the ADB state machine S0–S10), ADR-026 (committed device scope), ADR-020 (fail-closed Day-One + FRP), ADR-022 (allowlist deny-by-default), ADR-023 (enforcement-floor tiers), ADR-025 (pairing attestation + SAS), ADR-010 (no OS fork), ADR-002 (iOS parent app); docs/ANDROID_COMPAT.md §4/§5/§7 (note: ANDROID_COMPAT self-labels "v2+ scoping, superseded for current scope by ADR-023" — ADR-026/027 lift its §3/§4/§5/§7 into v1 prerequisite scope; ANDROID_COMPAT's status header should gain a matching note when those prerequisites are cut), docs/ROADMAP.md open question 3 (iOS child enforcement)
Amends: PROVISIONING_V2.md "Locked decisions" (relaxes *ADB-only* and *Pixel-7-only* for the consumer path)
Resolved-by: the ADR-025 Tier-2 amendment landed as **ADR-029** (Accepted, 2026-06-18). The pairing reuse is now defined for the committed Samsung/OnePlus targets (OEM-root allowlist + TEE-level acceptance + disclosed downgrade; four-key SAS mandatory; refuse-closed on *unknown* root). Remaining provisioning items (QR-OOBE flow, signed-APK/Play distribution, disclosure UI) are agent-blocked implementation tracked separately.

> **Amendment 2026-06-29 (issue #133):** the **Google Play Protect DPC allowlist** (live since late-2025) blocks Device-Owner provisioning **at OOBE** for a non-allowlisted DPC — so D2's "Play clearance is a tracked release prerequisite" now also gates the **D1 QR-OOBE primary path**, not just Play-Store install. See the **Amendment (2026-06-29)** section at the end.

## Context

Every strong defense in OpenWarden is downstream of one fact: the child app is **Device Owner (DO)**. Android sets a hard constraint on how DO can be established:

- DO can only be set on a device with **zero accounts**, and only via **(a) ADB** (`dpm set-device-owner`) or **(b) a QR / NFC provisioning flow during the out-of-box experience (OOBE) of a factory-reset device.**
- Once a Google account exists on the device — which installing from the Play Store onto an already-set-up phone requires — `dpm set-device-owner` **refuses permanently.**

Therefore **a Play-Store install onto a kid's existing phone can never become Device Owner**, and a "just download it and you're protected" product at our enforcement level is **not possible** — this is an OS rule, not a design choice.

PROVISIONING_V2.md currently specifies the **ADB/USB** path only (S0–S10, `adb shell dpm set-device-owner`) and lists "Pixel 7 child target … ADB" among its locked decisions. The maintainer wants to do **as much as possible from an app-store download with no USB/computer**, without lowering the protection level. The achievable answer is QR-OOBE provisioning: full DO strength, initiated entirely from the phone, app fetched from Play — at the cost of a factory reset + a QR scan, not a laptop.

## Options

- **A. ADB/USB only (status quo).** Strongest and fully scriptable, but every consumer needs a computer + cable. Rejected as the *only* path — it caps adoption to the technical.
- **B. QR-OOBE Device-Owner, app distributed via Play Store, ADB retained as the power/bench path (chosen).** Full DO strength with no computer for the common case; Play distribution for discoverability + auto-update.
- **C. Add a weak no-reset "regular app" mode (Play install, Accessibility + VPN + Device *Admin*, Family-Link-style).** Zero friction but **bypassable** (kid disables accessibility / uninstalls / Safe-Boots / factory-resets). Rejected — it cannot meet the fail-closed / anti-bypass non-negotiables, and shipping it as a "lite" tier would let a parent believe they are protected when they are trivially bypassed (the ADR-023 honesty bar applies to protection claims).

## Decision

**D1 — QR-OOBE Device-Owner provisioning is the PRIMARY consumer path.** On a factory-reset (or new) phone: at the OOBE "Hi there" screen, tap 6× to enter QR enrollment, scan a QR rendered by the parent app, and Android fetches the DPC and sets it as Device Owner — **no computer, no cable.** The DPC then runs the existing atomic Day-One sequence. This is the standard Android Enterprise / AOSP managed-provisioning mechanism.

**D2 — Distribution via the Google Play Store is the primary channel; signed-APK fallback is mandatory.** The QR provisioning payload carries the DPC component name and an install source. Primary source = Play. **Fallback = a signed-APK download location (HTTPS URL + pinned SHA-256) embedded in the QR**, used if Google Play policy rejects a parental-control DPC (a real risk — Play polices device-management + Accessibility apps; our no-content-monitoring posture helps but does not guarantee acceptance). **The authoritative trust anchor is the DPC *package signing-certificate* pin** — built into the parent app (pinned, version-controlled), and verified by AOSP managed provisioning against the *installed* package's signer (the `EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` mechanism) **before DO is set, on every channel**. The HTTPS URL is therefore **untrusted by construction**: a substituted APK at a substituted URL fails the signer pin. The SHA-256 (PROVISIONING_V2 §8) is a secondary integrity check, **not** the sole root — in particular the fallback URL's checksum must never be self-referential (an attacker who controls the URL must not also define the hash that "verifies" it). Play policy clearance is tracked as a release prerequisite, not assumed.

**D3 — ADB/USB desktop provisioning is retained as the power-user / bench / CI path.** PROVISIONING_V2's S0–S10 state machine is **not** removed; it stays the reference for scripted, headless, and bench-test provisioning, and the **emulator path stays required for dev** (no bench fleet for v1 dev). QR-OOBE is the consumer front door; ADB is the engine room.

**D4 — The weak no-DO mode (option C) is rejected.** OpenWarden does **not** ship a Play-install-without-reset "regular app" parental-control mode. No-USB is achieved by **QR-OOBE DO (D1)**, never by dropping to a bypassable Device-Admin/Accessibility app. "No-USB" must never silently become "no-DO."

**D5 — Trust + atomicity carry onto the QR path, but the atomicity basis is RE-DERIVED for it (not assumed identical to ADB).** PROVISIONING_V2 §3 closes the I3 reprovision window (DO set at S5, restrictions applied at S6) with *three* layers: (1) USB-tethered execution under host-driven airplane mode, (2) synchronous `DISALLOW_*` application inside `onProfileProvisioningComplete`, (3) the host `/health` poll gating S6→S7. The QR-OOBE consumer path has **no laptop, no tether, no host airplane-mode, no host poll** — so **Layers 1 and 3 do NOT transfer.** Only **Layer 2 transfers** (AOSP managed provisioning genuinely awaits `onProfileProvisioningComplete` before declaring success on the QR path too).
  - **QR-path I3 closure (replaces Layers 1/3):** the DPC must hold a **full-screen provisioning activity** from DO-set through restriction-apply — **no return to launcher / no kid-reachable surface** until an on-device self-check confirms the full `DISALLOW_*` set is applied and `fail_closed == true` (the on-device equivalent of the host `/health` poll), with restrictions applied synchronously in `onProfileProvisioningComplete` (Layer 2). This re-derivation — *not* "PROVISIONING_V2 §3 transfers verbatim" — is what closes I3 without a tether, and is itself agent-blocked policy-enforcement work.
  - **FRP bound last** (PROVISIONING_V2 §6) — unchanged.
  - **Identity = attested keys + SAS, not the channel.** Pairing uses the ADR-025 attested-key + (four-key) SAS handshake — but **on Samsung/OnePlus that requires the Blocked-on ADR-025 amendment**; ADR-025 as ratified has no TEE / OEM-root path, so this ADR does **not** claim ADR-025 already authorizes the Tier-2 pairing flow. The **provisioning QR** (sets DO) and the **pairing QR** (key exchange, PROVISIONING_V2 S8 / ADR-025 §7.1) are **distinct** artifacts; both need single-use, bounded-lifetime, burn-on-use anti-replay. The provisioning-QR nonce is a **new** artifact — its lifetime / single-use / abort-cleanup semantics must be added to **ADR-025's open "blocking before implementation" list** (it inherits the same unresolved nonce/endpoint items), not assumed handled.

**D6 — Per-OEM OOBE handled inside the QR/provisioning flow, not ADB scripts, for the consumer path.** Samsung (skip Smart Switch / Bixby / Knox / account screens), OnePlus (skip account screen), and the **battery-optimization exemption gate** (refuse to mark provisioning complete until the FGS exemption is granted) are performed in the guided QR-OOBE flow (ANDROID_COMPAT §5/§7). **If the parent declines the exemption dialog, the flow FAILS CLOSED — it must not complete-and-hope:** it either blocks provisioning completion or completes into the strict baseline (lock-task to OpenWarden, ADR-024) with the watchdog gap flagged to the parent — never a "done" state with an OEM-killable watchdog (which would be a silent fail-open, since a killed FGS stops the ADR-021 re-assert that bounds drift). This is the QR-path equivalent of the ADB script refusing to advance past S9 (ANDROID_COMPAT §5). The per-OEM `*.sh` hooks remain for the bench/ADB path (D3). This is the consumer-path realization of ADR-026 D5's per-OEM provisioning prerequisite.

**D7 — iOS child enrollment is NOT decided here; it remains an open v1.0 blocker** (ROADMAP open question 3). iOS has no Device-Owner equivalent. Recorded candidates, to be settled in a separate ADR: **Family Controls / Screen Time API** (App-Store app, Apple-Family-Sharing-enrolled, Screen-Time-passcode-locked — the no-USB candidate, but bounded by Apple's framework) vs **Supervised MDM** (Apple Configurator over USB once, or Apple Business Manager — strongest, but a one-time cable/enrollment step). This ADR governs the **Android** provisioning model only.

## Consequences

**Good:**
- The common case needs **no computer** — factory-reset the phone, scan a QR, done — while keeping **full Device-Owner protection**. This is the single biggest adoption unlock short of weakening the model.
- Play distribution gives discoverability + auto-update; the signed-APK fallback keeps us shippable even if Play says no.
- The security *model* is the same DO + FRP-last + attested pairing; the **I3-window atomicity basis is re-derived** for the no-tether path (D5), not inherited verbatim from the ADB tether.

**Bad / accepted limits / risks:**
- **A factory reset is still required** — this is *not* zero-friction; it is "no laptop," not "no setup." Honest onboarding must say so up front.
- **Play policy risk** for a parental-control DPC is real; D2's signed-APK fallback is the mitigation, and Play clearance is a tracked release prerequisite.
- QR-OOBE managed provisioning is **new engineering** (provisioning payload, Play/URL install source, the guided per-OEM OOBE flow) on top of the existing ADB path.
- The **emulator cannot validate** real-OEM QR-OOBE quirks — bench devices (Pixel + Samsung + OnePlus per ADR-026) are required to certify the consumer path.

**Security note:** QR-OOBE keeps the same Device Owner, FRP, and attested-pairing *model* as ADB, with the I3-window atomicity **re-derived** for the no-tether path (D5) rather than assumed — done right (full-screen provisioning hold + on-device self-check), it closes the same window without the cable. The only way "no-USB" could have *weakened* the model is the rejected option C (no-DO); D4 forecloses it. Fail-closed, no-SaaS/telemetry/content-monitoring, and recovery-phrase root authority all stand. Note this ADR is **Proposed**, not Accepted: the Tier-2 pairing reuse is gated on the Blocked-on ADR-025 amendment.

## Amendment (2026-06-29) — Google Play Protect DPC allowlist now gates the QR-OOBE path, not just Play install (issue #133)

Source: cross-OEM provisioning research, `docs/research/09-disallow-debugging-and-cross-oem-provisioning.md`.

**Finding.** D2 already tracks "Play policy clearance … as a release prerequisite," but framed it as a *Play-Store-install* risk mitigated by the D2 signed-APK fallback. The research surfaced a **stronger, newer gate**: since **late-2025 Google enforces a DPC allowlist during managed provisioning itself**. A DPC that is **not on Google's allowlist is blocked at OOBE** with a "Harmful app blocked" warning — i.e. the gate fires on the **D1 QR-OOBE primary consumer path**, the standard AOSP `android.app.action.PROVISION_MANAGED_DEVICE` flow, *before* DO is set. This is broader than "Play might reject the listing."

**Impact on the decisions:**
- **D1 (QR-OOBE primary path) is gated on DPC-allowlist approval.** A non-allowlisted OpenWarden DPC cannot complete QR-OOBE provisioning on a current device, regardless of Play-listing status. Allowlist approval is therefore a **hard release prerequisite for the primary consumer path**, elevated from D2's narrower "Play listing" framing.
- **D2 signed-APK fallback — does it bypass the allowlist?** **Unconfirmed.** The QR can carry a signed-APK HTTPS URL + `EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`; whether AOSP managed provisioning still consults the Play Protect allowlist when the APK is URL-fetched (not Play-fetched) must be **empirically confirmed on a current device** before relying on it as the allowlist escape hatch. Until confirmed, treat the allowlist as gating **both** channels.
- **Approval posture (favorable, not guaranteed).** Google's allowlist review requires: no surveillance-first capabilities, Mobile-Unwanted-Software compliance, no silent autoinstall, and passing human review on appeal. OpenWarden's **no-content-monitoring** non-negotiable + parental-control framing are favorable signals, but approval is **not guaranteed** and must be pursued + tracked as a release blocker (issue #133).

**Honesty note:** this does not relax any non-negotiable; it tightens an external dependency. It is recorded so the release plan does not assume the QR-OOBE path "just works" once the app is built — it additionally requires Google's DPC-allowlist clearance. This is an **external-dependency disclosure**, not a scope pivot.
