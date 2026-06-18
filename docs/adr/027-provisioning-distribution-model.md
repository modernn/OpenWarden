# ADR-027: Provisioning + distribution model — QR-OOBE Device-Owner is the primary consumer path; Play-distributed; ADB stays the power/bench path; the weak no-DO mode is rejected (amends PROVISIONING_V2)

Status: Accepted
Date: 2026-06-17
Relates: docs/PROVISIONING_V2.md (the ADB state machine S0–S10), ADR-026 (committed device scope), ADR-020 (fail-closed Day-One + FRP), ADR-022 (allowlist deny-by-default), ADR-023 (enforcement-floor tiers), ADR-025 (pairing attestation + SAS), ADR-010 (no OS fork), ADR-002 (iOS parent app); docs/ANDROID_COMPAT.md §4/§5/§7, docs/ROADMAP.md open question 3 (iOS child enforcement)
Amends: PROVISIONING_V2.md "Locked decisions" (relaxes *ADB-only* and *Pixel-7-only* for the consumer path)

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

**D2 — Distribution via the Google Play Store is the primary channel; signed-APK fallback is mandatory.** The QR provisioning payload carries the DPC component name and an install source. Primary source = Play. **Fallback = a signed-APK download location (HTTPS URL + pinned SHA-256) embedded in the QR**, used if Google Play policy rejects a parental-control DPC (a real risk — Play polices device-management + Accessibility apps; our no-content-monitoring posture helps but does not guarantee acceptance). **The DPC signature + checksum are verified before DO is set, on every channel** (supply-chain integrity, PROVISIONING_V2 §8). Play policy clearance is tracked as a release prerequisite, not assumed.

**D3 — ADB/USB desktop provisioning is retained as the power-user / bench / CI path.** PROVISIONING_V2's S0–S10 state machine is **not** removed; it stays the reference for scripted, headless, and bench-test provisioning, and the **emulator path stays required for dev** (no bench fleet for v1 dev). QR-OOBE is the consumer front door; ADB is the engine room.

**D4 — The weak no-DO mode (option C) is rejected.** OpenWarden does **not** ship a Play-install-without-reset "regular app" parental-control mode. No-USB is achieved by **QR-OOBE DO (D1)**, never by dropping to a bypassable Device-Admin/Accessibility app. "No-USB" must never silently become "no-DO."

**D5 — The trust + atomicity model carries onto the QR path UNCHANGED.** Everything PROVISIONING_V2 guarantees on the ADB path holds identically on the QR path:
  - **Atomic** — no kid-reachable window where DO is set but restrictions are not (the DPC applies the full `DISALLOW_*` set synchronously in `onProfileProvisioningComplete`; PROVISIONING_V2 §3).
  - **FRP bound last** (PROVISIONING_V2 §6).
  - **Identity = attested keys + SAS, not the channel** — pairing still uses StrongBox/TEE-attested Ed25519/X25519 keys + the six-emoji SAS (ADR-025). The **provisioning QR** (which sets DO) and the **pairing QR** (key exchange, PROVISIONING_V2 S8) are **distinct, single-use** artifacts; both must be specified with nonce/anti-replay.

**D6 — Per-OEM OOBE handled inside the QR/provisioning flow, not ADB scripts, for the consumer path.** Samsung (skip Smart Switch / Bixby / Knox / account screens), OnePlus (skip account screen), and the **battery-optimization exemption gate** (refuse to mark provisioning complete until the FGS exemption is granted) are performed in the guided QR-OOBE flow (ANDROID_COMPAT §5/§7). The per-OEM `*.sh` hooks remain for the bench/ADB path (D3). This is the consumer-path realization of ADR-026 D5's per-OEM provisioning prerequisite.

**D7 — iOS child enrollment is NOT decided here; it remains an open v1.0 blocker** (ROADMAP open question 3). iOS has no Device-Owner equivalent. Recorded candidates, to be settled in a separate ADR: **Family Controls / Screen Time API** (App-Store app, Apple-Family-Sharing-enrolled, Screen-Time-passcode-locked — the no-USB candidate, but bounded by Apple's framework) vs **Supervised MDM** (Apple Configurator over USB once, or Apple Business Manager — strongest, but a one-time cable/enrollment step). This ADR governs the **Android** provisioning model only.

## Consequences

**Good:**
- The common case needs **no computer** — factory-reset the phone, scan a QR, done — while keeping **full Device-Owner protection**. This is the single biggest adoption unlock short of weakening the model.
- Play distribution gives discoverability + auto-update; the signed-APK fallback keeps us shippable even if Play says no.
- Nothing about the security model changes — same DO, same atomicity, same FRP-last, same attested pairing.

**Bad / accepted limits / risks:**
- **A factory reset is still required** — this is *not* zero-friction; it is "no laptop," not "no setup." Honest onboarding must say so up front.
- **Play policy risk** for a parental-control DPC is real; D2's signed-APK fallback is the mitigation, and Play clearance is a tracked release prerequisite.
- QR-OOBE managed provisioning is **new engineering** (provisioning payload, Play/URL install source, the guided per-OEM OOBE flow) on top of the existing ADB path.
- The **emulator cannot validate** real-OEM QR-OOBE quirks — bench devices (Pixel + Samsung + OnePlus per ADR-026) are required to certify the consumer path.

**Security note:** QR-OOBE does not weaken anything relative to ADB — it is the *same* Device Owner, atomicity, FRP, and attested pairing, merely initiated without a cable. The only way "no-USB" could have weakened the model is the rejected option C (no-DO); D4 forecloses it. Fail-closed, no-SaaS/telemetry/content-monitoring, and recovery-phrase root authority all stand.
