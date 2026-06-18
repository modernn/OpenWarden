# ADR-026: Commit the top OEMs (Pixel + Samsung + OnePlus) to the v1.0 release, at the disclosed-gap floor (amends ADR-001 / ADR-023)

Status: Proposed
Date: 2026-06-17
Relates: ADR-001 (device-tier system), ADR-023 (tiers defined by enforcement floor + disclosed Tier-2 gaps), ADR-010 (no OS fork; stock Android primary), ADR-020 (fail-closed Day-One + FRP/OEM-unlock), ADR-022 (allowlist deny-by-default), ADR-016 (DNS floor), ADR-019 (canonical signing), ADR-025 (pairing attestation + SAS); docs/ANDROID_COMPAT.md, docs/CRYPTO.md §3/§10, docs/PROVISIONING_V2.md, docs/ROADMAP.md
Amends: ADR-001 (release timing of Tier 2), ADR-023 (which Tier-2 models are *release-committed*)
Blocked-on (before this flips to Accepted / before any code): a dedicated ADR amending **ADR-025 D2/D7 + reconciling CRYPTO.md §3/§10** to define the Tier-2 attestation posture — an **OEM-root allowlist** + **TEE-level attestation acceptance** + a **parent-disclosed downgrade**, with the four-key SAS still mandatory and **fail-closed pair-refusal on any *unknown* root**. ADR-025 currently ratifies *Google-root + StrongBox attestation or refuse-the-pair*, which the committed Samsung/OnePlus targets (TEE-only, OEM roots — ANDROID_COMPAT §3) **cannot satisfy** — so this device commitment is *unbuildable* until that amendment lands. It is the first item in D5.

## Context

ADR-001 adopted a device-tier system and ADR-023 redefined the tiers by their **enforcement floor**, recording honestly that Tier-2 OEM devices are *measurably more bypassable* than Tier-1 Pixel-class hardware (OEM-preloaded `FLAG_SYSTEM` browser/store stay launchable past the deny-by-default allowlist, factory-reset / OEM-unlock is best-effort, no StrongBox guarantee, and the FGS watchdog depends on a per-OEM battery-optimization exemption). The committed **release** posture that fell out of those ADRs — reinforced by ANDROID_COMPAT.md §10 — was **Tier 1 (Pixel) at v1.0, with Tier-2 (Samsung / OnePlus / Motorola / Nothing) deferred to a v2.0 epic.**

The maintainer has decided that shipping Pixel-only at the public release is not acceptable: Pixel is ~3% of global Android, so a Pixel-only release excludes essentially every family that does not already own a Pixel. The product must work on the **most common Android phones from the v1.0 release**, not a version later.

Two forks were resolved by the maintainer before this ADR was written:

1. **Which OEMs.** "Most popular" (Xiaomi/Oppo/Vivo) and "most enforceable" (Samsung/Motorola/OnePlus/Nothing — clean AOSP DPC, well-behaved provisioning) point at different phones. The Chinese-ecosystem OEMs are both the highest-share *and* the worst enforcement surface (HyperOS kills the watchdog overnight, `DISALLOW_OEM_UNLOCK` is a silent no-op against the Mi-Unlock tool, account entanglement at OOBE — ANDROID_COMPAT §2/§5). **Decision: Pixel + Samsung + OnePlus.** Samsung is the #1 non-Pixel share and a clean DPC target with extra OOBE friction; OnePlus is near-AOSP. This keeps the bench matrix small (3 devices) and avoids committing the release to the OEMs ADR-023 deliberately left at Tier 3.
2. **Release floor.** **Decision: ship with disclosed enforcement gaps** (the ADR-023 D2 model), not gate the release on the per-OEM hardening backlog (#57). The product is honest about what is weaker on these devices and the parent acknowledges it at pairing; the release does not wait for the sub-allowlist hardening to land.

This is a **pivot** under the protected-roadmap mechanism (ADR-018): it changes what the v1.0 *release* commits to. It is recorded here with the ROADMAP update and milestone re-sync in the same PR.

## Decision

**D1 — The v1.0 release commits to three device targets:**

| Target | Tier (ADR-023) | Release posture |
|---|---|---|
| **Pixel 6+, Android 14+ stock** | Tier 1 | Full anti-bypass enforcement. The reference. |
| **Samsung Galaxy S22+ / A55+ / Note** | Tier 2 | Supported with **disclosed enforcement gaps** (ADR-023 D2). |
| **OnePlus 11+** | Tier 2 | Supported with **disclosed enforcement gaps** (ADR-023 D2). |

Motorola Edge 50+ and Nothing Phone 2+ remain **Tier-2-supported but not release-gating** — they may light up via the same per-OEM seam, but the v1.0 bench/QA matrix and the release claim cover only the three above. Xiaomi and everything else stay **Tier 3, best-effort, no anti-bypass warranty** (unchanged).

**D2 — Release floor for Samsung + OnePlus = the ADR-023 disclosed-gap floor, NOT a hardened guarantee.** The DPC works and the structural `DISALLOW_*` baseline applies, but the disclosed Tier-2 gaps stand at release:
1. OEM-preloaded browser/store launchable past the allowlist;
2. best-effort reset/unlock resistance;
3. **TEE-only key storage *and* TEE-level / OEM-root attestation** — the attestation half is a real reduction in the pairing-time hardware-genuineness (anti-device-swap, CRYPTO §14 H3) proof, **not just at-rest key isolation**; it must be disclosed as its own gap, not folded into "no StrongBox";
4. watchdog liveness contingent on a per-OEM battery exemption.

**No gap is allowed to fail open — but two are *conditional* on the D5 prerequisites, and this ADR does not claim otherwise:**
- Gaps (1) and (2) fail closed *in direction today*: the OEM-store-**installed** app is still suspended next tick (ADR-022 D4); reset/unlock degrades to an honest "can't guarantee" backstopped by heartbeat-silence (ADR-024).
- Gap (3) is fail-closed **only once the Blocked-on ADR-025 amendment makes the pair refuse-closed on an *unknown* root** — until then an unverified device could pair, which would be fail-open.
- Gap (4) is fail-closed **only if provisioning refuses to complete (or holds strict baseline) when the battery exemption is declined** (ADR-027 D6) — a complete-and-hope path would be a silent fail-open.

**D3 — Honest disclosure becomes release-gating for these two OEMs.** ADR-023 D5 (surface the Tier-2 gaps at provisioning/onboarding) was a pending follow-up; for any device this ADR commits to the release, the pairing/onboarding flow **must** show the parent the per-device "what's weaker" summary and require acknowledgement before completing setup. The summary **must include the attestation downgrade** (D2 gap 3 — TEE-level / OEM-rooted, a weaker hardware-genuineness proof than Pixel's StrongBox + Google-root), not only the allowlist/reset/watchdog gaps. Shipping a release-committed Tier-2 device *without* that disclosure would be the silent over-promise the non-negotiables forbid — so the disclosure UI is a hard release prerequisite, not optional polish.

**D4 — Per-OEM *hardening* (#57) gates the marketing/"fully hardened" claim, not the release.** The curated system sub-allowlist that suppresses the OEM browser/store, per-OEM FRP/unlock verification, and per-OEM watchdog verification (ADR-023 D4 / issue #57) remain the path to a *hardened* Tier-2 claim. The release ships at the disclosed-gap floor without them; we must not advertise Samsung/OnePlus as "fully protected" until #57 lands for that OEM.

**D5 — Release prerequisites (engineering, tracked as agent-blocked issues; pulled into the pre-v1.0 committed scope when their rung comes).** This ADR is the *decision*; like ADR-023 it does not by itself change enforcement code. To make the three-device release real, the following must land (each crypto/provisioning/policy-enforcement = human-gated):
  - **Amend ADR-025 (D2/D7) + reconcile CRYPTO.md §3/§10 — the load-bearing prerequisite (see Blocked-on).** ADR-025 ratified *Google-root + StrongBox attestation or fail-closed pair-refusal*; the committed Samsung/OnePlus targets are TEE-only with OEM (Knox/Snapdragon) roots and therefore **cannot pair** under it as written. A dedicated crypto ADR must re-open ADR-025's trust model to: an **OEM-root allowlist** (Google + Samsung Knox + OnePlus), **TEE-level attestation acceptance** with the downgrade **disclosed** (D2 gap 3 / D3), the **four-key SAS still mandatory**, and **fail-closed refusal on any *unknown* root**. This is agent-blocked crypto canon and gates every item below — nothing else in D5 is buildable until it lands.
  - **StrongBox→TEE fallback** in CRYPTO.md §3: Tier 1 requires StrongBox; Tier 2 (Samsung Knox Vault where present — flagships only; the committed A55 is TEE-only / OnePlus generally TEE) uses StrongBox if available else TEE; never fail provisioning for absence of StrongBox; pin the TEE attestation chain (ANDROID_COMPAT §3 fallback rule).
  - **`oem_roots.json` attestation allowlist** shipped in `:shared:crypto` with the Google, Samsung Knox, and OnePlus attestation roots; unknown root → `unverified_attestation=true` flag, never an over-claim (ANDROID_COMPAT §4).
  - **Per-OEM provisioning under the QR-OOBE consumer path (ADR-027).** The Samsung and OnePlus OOBE handling (skip Samsung Smart Switch/Bixby/Knox + OnePlus account screens; battery-optimization exemption gate that refuses to mark provisioning complete until the FGS exemption is granted — ANDROID_COMPAT §5/§7) is realized in the guided QR-OOBE Device-Owner flow per **ADR-027 D6**; the equivalent `samsung.sh` / `oneplus.sh` hooks remain for the ADB/bench path. QR-OOBE is the primary consumer path for these devices; ADB is the power/bench path.
  - **Pairing-time gap-disclosure UI** (D3).
  - **Bench QA sweep** — the ATTACKS A-class attack matrix run on Pixel + Samsung + OnePlus; the emulator does not validate per-OEM quirks (ANDROID_COMPAT §8).

## Consequences

**Good:**
- The release works on the phones families actually own: Pixel + Samsung covers the large majority of the realistic install base, OnePlus adds a clean near-AOSP target. Reach at release jumps from ~3% (Pixel-only) toward the Samsung-inclusive majority.
- Honest by construction: the disclosed-gap floor (D2) + the release-gating disclosure (D3) mean a parent on a Samsung is told exactly what is weaker, before they rely on it. No silent over-promise.
- Small, real test matrix (three devices), not "all of Android."

**Bad / accepted limits:**
- We ship a **genuinely more bypassable** product on Samsung/OnePlus at release than on Pixel, and we say so out loud. That is the conscious trade for reach (the maintainer chose disclosed-gaps over harden-first).
- D5 is real human-gated engineering (TEE fallback, attestation roots, per-OEM provisioning, disclosure UI, bench QA) that must land before the release can claim these devices — it pulls Tier-2 work *into* the pre-v1.0 critical path that ADR-001/ANDROID_COMPAT had parked at v2.0.
- The test/triage cost rises per committed OEM; Motorola/Nothing/Xiaomi stay non-gating precisely to bound that cost.

**Security note:** committing these OEMs does **not** relax any non-negotiable. Fail-closed still holds on every path (D2); the DNS floor (ADR-016), signed-bundle verification (ADR-019), sealed-box event log, and replay floors are all transport/OEM-agnostic and unchanged. The *only* thing that moves is the honest, parent-acknowledged enforcement-strength disclosure — which this ADR makes a hard release gate (D3) rather than a deferred nicety.
