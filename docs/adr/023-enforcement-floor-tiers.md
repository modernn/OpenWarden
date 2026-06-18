# ADR-023: Device tiers are defined by their enforcement floor, not just attestation (amends ADR-001)
Status: Accepted
Date: 2026-06-16
Relates: ADR-001 (one device tier), ADR-020 (fail-closed Day-One + FRP/OEM-unlock), ADR-022 (allowlist deny-by-default), ADR-016 (DNS floor); docs/ANDROID_COMPAT.md, docs/research/07
Amends: ADR-001 (tier definitions + Consequences)

> **Amended by ADR-026 (2026-06-17):** **Samsung (S22+/A55+/Note) + OnePlus 11+ are now
> release-committed at v1.0**, shipping at this ADR's **disclosed-gap floor (D2)** — not a hardened
> guarantee. Two consequences for this ADR: (1) the **D5 onboarding/pairing disclosure** of the
> Tier-2 gaps becomes a **hard release gate** for those two OEMs (shipping them without it would be
> the silent over-promise the non-negotiables forbid); (2) the **D4 per-OEM hardening backlog (#57)**
> now gates the *"fully hardened" marketing claim*, **not** the release itself. Everything else here
> (the enforcement matrix, the honest Tier-2/3 language, fail-closed) is unchanged. See ADR-026.

Depends on: **ADR-022 / PR #56 (landed first, as required).** This ADR's enforcement matrix and Tier-2 rows cite ADR-022 (esp. D4, the `FLAG_SYSTEM` residual). PR #56 was merged ahead of this PR, so the `022` file exists, every citation resolves, and the index is contiguous (021 → 022 → 023) and auditable.

## Context

ADR-001 adopted a **device-tier system** (Tier 1 Pixel-class / Tier 2 named OEMs / Tier 3 any
DPC-capable Android) to grow reach ~5× beyond Pixel-only. It defined the tiers mostly by
**attestation and crypto strength** — "StrongBox + AVB Google chain" for Tier 1, "attestation
per-OEM root, FGS watchdog more aggressive" for Tier 2 — and described Tier 1 as having "tight
`DISALLOW_*` enforcement," implying enforcement is roughly uniform below it.

Building the actual enforcement surface showed that is not true. The thing a parental control
*sells* — **anti-bypass enforcement** — degrades on Tier 2/3 in ways the ADR-001 table does not
capture, and the degradation is now concrete:

- **Launch allowlist (ADR-022 / #12).** The deny-by-default allowlist exempts all `FLAG_SYSTEM`
  apps (suspending system components bricks the device). On a Tier-1 Pixel the system image is
  AOSP+Google-clean, so this is tight. On a Tier 2 device (the specific tested models in D1) the
  OEM **preloads** `FLAG_SYSTEM` apps — e.g. Samsung Internet, Galaxy Store — that
  the allowlist therefore cannot suspend. A kid can launch the OEM-preloaded browser/store past the
  allowlist. (Heavier-customization OEMs such as Xiaomi MIUI are **Tier 3**, not committed Tier 2 —
  see SIMPLIFY.)
- **Factory-reset / OEM-unlock (ADR-020 D4/D5).** FRP and `DISALLOW_OEM_UNLOCK` are reliable only
  on Pixel-class hardware with a locked bootloader; on much of Tier 2 they are bypassable via
  vendor unlock tools (research/07 S1: Xiaomi Mi-Unlock, OnePlus toggle).
- **Hardware key storage.** StrongBox is present on only ~15–20% of the market (ADR-001's own
  Consequences); Tier 2/3 fall back to TEE.

A parent on a Samsung phone gets a meaningfully **more bypassable** product than "works with
documented caveats / attestation per-OEM root" conveys. Codex and the dual adversarial review on
#12 both surfaced this as a real over-promise that should be recorded, not papered over.

The non-negotiable in play: we do not silently over-claim a protection. ADR-001's tiers must tell
the truth about *enforcement*, not only attestation.

## Options

- **A. Leave ADR-001 as written.** Rejected — it over-promises Tier 2/3 enforcement; a parent
  cannot tell that the launch allowlist and reset protections are materially weaker on their
  device.
- **B. Narrow committed scope to Tier 1 (Pixel-class) only; demote Tier 2/3 to experimental.**
  Honest and safe, but reverses the explicit broad-Android user requirement that drove ADR-001
  and cuts committed reach from ~25% back toward ~3%. Rejected as the default.
- **C. Keep the tier system; redefine tiers by their *enforcement floor*, document the concrete
  Tier 2/3 gaps, publish a per-tier enforcement matrix, and put per-OEM hardening on the backlog
  (chosen).** Keeps reach and the broad-Android promise, tells the truth, and defers the heavy
  per-OEM engineering to tracked work rather than blocking now.

## Decision

**D1 — Tiers are defined by their enforcement floor, not just attestation.** ADR-001's tier table
is extended with the **enforcement matrix** below. A device's tier is the *weakest* row it
satisfies; a tier claim is only honest if every enforcement guarantee at that tier holds.

**Canonical Tier-2 device list (= ADR-001's, used everywhere in this ADR):** Samsung Galaxy
S22+ / A55+ / Note, OnePlus 11+, Motorola Edge 50+, Nothing Phone 2+. Heavier-customization OEMs
(e.g. Xiaomi MIUI) are **Tier 3**, not committed Tier 2 (SIMPLIFY). The matrix rows, the Context
examples, and the D4 hardening backlog all use this one list — no drift.

| Enforcement surface | Tier 1 (Pixel 6+, A14+ stock) | Tier 2 (specific tested models — the canonical list above) | Tier 3 (any other / older A13+ DPC) |
|---|---|---|---|
| Launch allowlist (deny-by-default) | Tight — clean system image | **Gap: OEM-preloaded `FLAG_SYSTEM` apps (browser/store) stay launchable** (ADR-022 D4) | Gap, unaudited |
| Factory-reset / OEM-unlock resistance | Strong (locked bootloader + FRP) | **Best-effort — vendor unlock tools bypass** (ADR-020 D4/D5) | None guaranteed |
| Day-One `DISALLOW_*` baseline | Full, verify-or-throw | Full where the OEM honors the keys; some keys may no-op | Best-effort |
| Profile-escape block + containment | Full (ADR-022) | Full where the OEM honors the keys | Best-effort |
| DNS fail-closed floor (ADR-016) | Pinned + locked | Pinned; per-OEM Private DNS UI variance | Best-effort |
| **Watchdog / FGS liveness (ADR-021)** | Reliable — no aggressive battery killer | **Contingent on a per-OEM battery-optimization exemption / provisioning** — OnePlus/Samsung (and heavier OEMs like Xiaomi MIUI, a Tier-3 case) can kill the foreground service, which stops the re-assert that bounds drift (ANDROID_COMPAT) | Best-effort |
| Hardware key storage | StrongBox | TEE fallback (no StrongBox guarantee) | TEE / none |

**D2 — Honest tier language (replaces ADR-001's attestation-only descriptions).**
- **Tier 1 — full support:** every enforcement guarantee above holds.
- **Tier 2 — supported with *disclosed enforcement gaps*:** DPC works and most `DISALLOW_*` apply,
  **but** the launch allowlist can be bypassed via OEM-preloaded apps, reset/unlock protection is
  best-effort, watchdog liveness depends on a per-OEM battery exemption, and there is no StrongBox
  guarantee. These gaps **must be disclosed to the parent** — propagated into the parent-facing
  docs (README + SIMPLIFY are reconciled in this PR; the setup/onboarding flow is still pending,
  see D5), not silently over-promised.
- **Tier 3 — best-effort, community-supported, *no anti-bypass warranty*.**

**D3 — Tier 2/3 stay SUPPORTED with these caveats.** This amendment does **not** drop Tier 2/3
(that was option B). The broad-Android decision of ADR-001 stands; what changes is that the
*enforcement* limits are now first-class and **must be disclosed** (D5), mitigated where possible
(e.g. the DNS floor still filters the OEM browser's web egress; an app *installed* via the OEM
store is itself a non-allowlisted user app the deny-by-default allowlist suspends within a tick).
**Install-approval is NOT a current mitigation** — it is frozen/future scope, not implemented, and
must not be cited as a present defense.

**D4 — Per-OEM enforcement hardening is a tracked backlog item, gated before any "Tier 2 fully
hardened" / marketing claim.** Scope: a curated per-OEM system **sub-allowlist** (suspend/hide
OEM-preloaded browser/store while keeping launcher/SystemUI/dialer exempt), per-OEM FRP/unlock
verification, **per-OEM watchdog/battery-optimization verification**, and per-OEM provisioning.
Filed as **issue #57** (`area:child-android`, `agent-blocked`). Until it lands, Tier 2 ships with
the D2 disclosure, not a hardened guarantee.

**D5 — Marketing / docs disclosure (partly landed here, onboarding pending).** This ADR is the
decision; the parent-facing docs must carry the Tier-2/3 enforcement caveat so the "disclosure" of
D2/D3 is real, not silent over-promise (the no-SaaS honesty bar applies to protection claims too).
**Reconciled in this PR:** `README.md` (now states the enforcement tiers instead of a flat
"supported"/"best on Pixel, works on most") and `docs/SIMPLIFY.md` (the "one device family /
Pixel-only" constraint is replaced with the ADR-001/ADR-023 tier-and-disclose model, removing the
contradiction). **Still a follow-up:** the setup/onboarding flow must surface the Tier-2 gaps at
provisioning — tracked alongside #57's gating, required before any Tier-2 marketing or beta.

## Consequences

Good:
- The tier table now tells the truth about enforcement, the dimension a parental control is judged
  on — closing the over-promise Codex/the review flagged.
- Broad-Android reach and the ADR-001 decision are preserved; nothing is dropped.
- The concrete Tier 2/3 gaps (allowlist, reset/unlock, StrongBox) are enumerated in one place and
  have a named owner (the per-OEM hardening backlog), instead of being scattered residuals.

Bad / accepted limits:
- Tier 2/3 is genuinely weaker, and we now say so out loud — a real (and correct) reduction in how
  strong we can claim the product is on the most common phones.
- Per-OEM hardening is real future engineering (sub-allowlist + provisioning + verification per
  OEM), and the test matrix grows with each supported OEM.
- Marketing must carry nuance ("best on Pixel; works with disclosed limits on others"), which is
  harder to message than a flat "works everywhere."
- This is a documentation/strategy amendment: it does not by itself change any enforcement code.
  The Tier-2 allowlist gap remains until D4's per-OEM hardening ships.
