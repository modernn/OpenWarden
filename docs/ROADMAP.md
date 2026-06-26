# Roadmap

> **PROTECTED CANON.** This file is the single source of truth for version
> scope. It is CODEOWNERS-gated (`@modernn`). GitHub milestones **mirror** the
> near-term rungs here — this file is the source, the milestones are the copy.
>
> **This ladder is dynamic.** Only the *current* and *next* rung are committed;
> everything below them is a sketch that we expect to re-cut as we learn. When
> work uncovers a re-scope — split a rung, reorder, drop or add one, or change
> what a version *means* — that is a **pivot**: it needs an **ADR** in
> [`docs/adr/`](adr/) (or an amendment note on an existing ADR), this file
> updated, and the GitHub milestones re-synced, **all in the same PR**. Don't
> silently drift the plan; record the turn.

## Version semantics — read this first

The version numbers mean specific things. Don't confuse "it works for one kid"
with "it's released."

- **v0.x — the pre-release road.** Everything from first boot to a public,
  cross-platform, app-store-ready beta. Internal proofs (including *"Oliver's
  phone works"*) live here. Rungs are discovered and re-cut as we go. Breaking
  changes are fine; there is no public install base to protect yet.
- **v1.0 — the public release.** iOS **and** Android, **both** the parent and
  the child apps, shipped to the app stores, *after* a public beta. The bar is
  **parity with the commercial tools**: if Bark can ship a working iOS app, so
  must we. v1.0 is a distant north star, not the next thing — it is gated on a
  beta, not on Oliver's phone working.
- **post-1.0 (v1.x / v2+).** The smarter, economy, and AI-assisted features.
  Deferred until the cross-platform base is shipped and stable.

`v0.x` work is *frozen-design-tier free*: build it. `v1.0`-and-beyond bullets
are **frozen design** — do not implement them ahead of their rung without an ADR
(see [`CLAUDE.md`](../CLAUDE.md) → *Doc tier system*).

---

## The pre-release ladder (v0.x)

Provisional. The current and next rung are committed; lower rungs are a best
guess and will be re-cut by ADR. GitHub milestones mirror these.

### v0.1 — DPC foundation ✅ *(complete)*

The bedrock: the child app can become **Device Owner**, persist and verify a
**signed** policy bundle, and hold day-one restrictions across reboot. Nothing
else in child-android works until this lands.

- [x] `AdminReceiver` + manifest + `device_admin.xml` *(#7 — bedrock)*
- [x] `PolicyStore` — persist/load signed bundles to internal storage *(#9)*
- [x] `BundleVerifier` — Ed25519 signature check vs pinned parent pubkey *(#10 — crypto + ADR-019)*
- [x] `PolicyEnforcer` wraps `DevicePolicyManager` — fail-closed verify-or-throw + FRP *(#8, ADR-020)*
- [x] Day-one restrictions applied at boot: `DISALLOW_FACTORY_RESET`,
  `DISALLOW_CONFIG_VPN`, `DISALLOW_DEBUGGING_FEATURES`,
  `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`, `DISALLOW_MODIFY_ACCOUNTS`,
  `DISALLOW_ADD_USER`, `setFactoryResetProtectionPolicy(parent account)` *(#8 — canonical 17, DEFENSES row 2)*

### v0.2 — child enforcement surface ✅ *(complete)*

The restrictions a parent actually feels, plus the transparency screen that
keeps us on the right side of the stalkerware boundary.

- [x] Lock down accessibility services *(#13)*
- [x] Kid Transparency / "Why am I blocked?" screen — lists **all** monitored
  categories *(#14)*
- [x] Chrome managed policy — `URLBlocklist` (hidden browsers, proxies,
  `data:`/`blob:`) *(#16)*
- [x] Lock-screen / QuickSettings / camera lockdown *(#17)*
- [x] PolicyService FGS watchdog — reassert on boot/connectivity/timer/apply *(#11, ADR-021)*
- [x] DNS floor — pin Private DNS to a public *filtering* resolver, fail-closed, never OFF *(#19, ADR-016)*
- [x] App allowlist via `setPackagesSuspended` (visible + grayed + admin message); allowlist-only launch, deny-by-default *(#12, ADR-022)*
- [x] Progressive ratchet to strict baseline after N hours no-contact *(#18, ADR-024)*

### v0.3 — parent app MVP (Android) *(current)*

A parent can pair, see state, and push a policy — from a phone.

- [x] **parent-kmp scaffold** — `:proto` + `:shared` + `:androidApp` build green
  (Android). iOS host-gated to macOS, built later.
- [ ] Pair via QR scan — re-scoped per ADR-025 D5 into #94–#98 (parent *displays* QR;
  the parent-scans inversion is forbidden). (a) session + §7.1 QR payload done
  *(#94, PR #99, ADR-035)*; (b) §7.2 endpoint + D6 validation done *(#95, PR #101,
  ADR-036)*; (c) §7.3 attestation verify done *(#96, PR #103, ADR-037)*; (d) six-emoji
  SAS done *(#97, PR #105, ADR-038)*; (e) pin on Match done *(#98, PR #107, ADR-039)*;
  (f) orchestration controller + transport lifecycle + Compose QR/SAS UI wired *(ADR-043)* —
  the parent half now runs end-to-end from the app (show QR → receive POST → attestation →
  six-emoji compare → pin), discharging the ADR-039 D5a residual. The **child side** (QR scan +
  §7.2 POST + child-side SAS compute + child pin of the parent keys, §7.5) and parent-side
  **mDNS advertising** remain before full end-to-end pairing works.
- [x] Dashboard: child online status, today's usage, recent blocks *(#25, PR #67)*
- [x] App allowlist editor: pull installed apps from child, toggle allowed *(#26, PR #77)*
- [x] "Lock now" / "Unlock now" *(#28, PR #76)*
- [x] Generate Ed25519 root key, show 24-word recovery phrase, force confirm *(#24, PR #86, ADR-033)*
- [x] Send signed policy bundles *(#27, PR #89, ADR-034)*
- [ ] **Transport: LAN-only default** (mDNS discovery, no services). Tailscale /
  WireGuard modes deferred.

### v0.4 — provisioning + CI hardening

Make setup repeatable and make the gates real before inviting anyone in.

- [ ] Finalize `docs/PROVISIONING.md` + one-page printable setup *(#29)*
- [x] E2E: provision Pixel 7 from factory in <30 min — exit-criteria runbook +
  `connectedAndroidTest` *(#30, PR #122 — criteria 1 (DO) + 3 (latency) automated; criterion 2
  (restrictions intact) is verified out-of-band because enforcing `DISALLOW_DEBUGGING_FEATURES`
  disables ADB)*
- [x] CI: enforce ktlint + unit tests + `connectedAndroidTest` gating on PRs *(#31, PR #125,
  ADR-044 — ktlint (whole tree) + JVM unit tests gate every PR; `connectedAndroidTest` deferred
  to #124, the `DISALLOW_DEBUGGING_FEATURES`/ADB-severance constraint)*
- [ ] Contributor autopilot — `/openwarden` role-picker + agent-ready routing *(#32)*

### "Oliver's phone works" — internal proof (lands within v0.2–v0.4)

Not a release. The first end-to-end proof on the bench/real device:

- Provision a Pixel 7 from factory state in under 30 min.
- Oliver-as-owner phone restarts → self-unlocks → restrictions intact.
- Block + unblock an app from the parent phone, sub-5-sec latency.
- Survive a 7-day uptime test on the bench device.

### v0.5+ — toward public beta *(sketch — re-cut on arrival)*

Loose on purpose. Candidate rungs before we call anything a beta:

- iOS parent app reaches feature parity with the Android parent app (built on a
  Mac; the cross-platform bar starts here).
- Multi-child support hardened (schema already allows it; UX does not yet).
- Onboarding a non-technical parent without hand-holding.
- **Top-OEM release readiness (ADR-026/027 — Accepted):** the **load-bearing ADR-025 Tier-2
  attestation amendment landed as ADR-029** (OEM-root allowlist + TEE-level acceptance + parent-disclosed
  downgrade; four-key SAS still mandatory; fail-closed on *unknown* root), so the device commitment is
  buildable. Remaining: QR-OOBE Device-Owner
  provisioning (Play-distributed; signed-APK + URL fallback if Play policy rejects the DPC, trust
  anchored on the package-signer pin); StrongBox→TEE crypto fallback + per-OEM attestation roots
  (`oem_roots.json`: Google + Samsung Knox + OnePlus); per-OEM OOBE handling + battery-optimization
  exemption gate that **fails closed if declined** (Samsung/OnePlus); pairing-time enforcement-gap
  **disclosure UI** incl. the attestation downgrade (release-gating for Tier-2, ADR-023 D5); bench QA —
  the ATTACKS A-class sweep on Pixel + Samsung + OnePlus (the emulator can't validate per-OEM quirks).
  Each crypto/provisioning item is human-gated. **These are v0.5+ candidates that become committed
  when this rung is explicitly cut; the firm commitment lives in the v1.0 definition-of-done below.**
- Public beta program: real families, real devices, a feedback loop, crash/bug
  reporting **without** telemetry (opt-in, local, or manual reports only).

---

## v1.0 — public cross-platform release (north star)

**Definition of done — all of:**

- iOS **and** Android, **both** parent and child apps, on the app stores.
- Survived a public beta with real families.
- Parity-with-commercial bar: a parent could switch from Bark/Qustodio/etc. and
  not lose core function — and it must genuinely **work on iOS**, not just exist.
- **Committed child-device support (Android):** Tier 1 **Pixel** (full enforcement) +
  Tier 2 **Samsung** (S22+/A55+/Note) **& OnePlus 11+** at the *disclosed-gap floor*
  (ADR-023/ADR-026) — the parent is shown what's weaker at pairing, fail-closed preserved.
  Motorola/Nothing are supported-but-not-release-gating; Xiaomi/others are Tier-3 best-effort.
  *(pivot — ADR-026, Accepted; ADR-025 amended by ADR-029)*
- **Provisioning (Android):** **QR-OOBE Device-Owner is the primary consumer path** — factory-reset
  phone + scan a QR + DPC installs from the Play Store, **no computer**; ADB/USB stays the
  power/bench path. No weak no-DO ("regular app") mode ships. *(pivot — ADR-027, Accepted)*
- **iOS child enforcement model decided + shipped** — currently an **open blocker** (see open
  question 3); the no-USB candidate is Apple's Family Controls/Screen Time API, the strong path is
  Supervised MDM. Needs its own ADR before this rung closes. *(ADR-027 D7)*
- Every non-negotiable still holds: no SaaS, no telemetry, no content
  monitoring, fail-closed, recovery-phrase root authority, local-only.

Everything required to clear that bar gets pulled up from the v0.x sketch into a
committed rung *when its turn comes*, via the pivot mechanism above.

---

## Beyond 1.0 (frozen design — do not implement without an ADR)

Pulled forward from the old v2/v3/v4 plans. Direction, not commitment.

- **Transport modes:** self-hosted WireGuard; Tailscale (opt-in, Tailnet Lock on
  by default); mode-picker UI, reconfigurable post-pairing.
- **Store-and-forward log sync** as primary comms (REST stays the LAN transport).
- **Time windows** per app; **install-approval** one-tap flow; **daily digest**;
  **geofence** with on-device enforcement; **policy presets** ("school",
  "family time").
- **Desktop parent:** macOS / Windows builds; pair via QR-on-phone → camera-on-laptop.
- **Local AI (opt-in, on-device only):** screenshot NSFW classifier, then text
  classifier via Gemma Nano / AICore. Zero data egress; alerts to parent only.
- **Time bank** economy: earn screen time, redeemed via parent-signed token.
- **First grant application:** NLnet NGI Zero PET.
- **F-Droid release**; independent security audit (grant-funded); i18n.

## Never (probably)

- **Cloud content analysis.** All AI stays on-device.
- **Continuous audio capture, screen recording, keystroke logging.** Stalkerware.
  Not shipping.
- **Multi-tenant SaaS.** Single family is the unit, even at v1.0 scale.
- **Vendor-paid hosted demo.** COPPA risk + violates funding constraints.
- **Subscription tiers, paid features, paid enterprise.** Never. See
  [`docs/FUNDING.md`](FUNDING.md).

## Open architectural questions

1. **Tailscale daemon shipping:** embed the Tailscale Go client into the parent
   app, or rely on the user installing Tailscale separately? Embedding = better
   UX, more compliance surface. Deferred.
2. **iOS parent backgrounding:** is APNs silent push reliable enough for "child
   needs more time"? May need a self-hosted relay — which dents the "no server"
   story. This is on the v1.0 critical path now that iOS is in-scope for release.
3. **iOS child enforcement:** Android Device Owner has no iOS equivalent. What is
   the iOS child-side enforcement model (Screen Time API / MDM profile /
   Supervised), and does it clear the parity bar? **Open v1.0 blocker.**
4. **Parent data loss:** if the parent loses their phone, the child can't be
   updated until the parent restores from the recovery phrase. Acceptable — but
   should the *child* also hold an emergency-unlock token signed by an offline
   backup key?
5. **OTA bundle expiry:** auto-expire bundles to force re-sync? Research says yes.
   Default 30 days, tunable.

## Build the base airtight before you generalize

v0.1 still ships for one kid (Oliver, Pixel 7) and one parent (Larson). Get the
one-device, one-platform case airtight first — *then* climb the ladder toward the
cross-platform v1.0 release. The destination is the world; the first rung is one
phone.
