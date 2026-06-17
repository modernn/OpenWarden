# ADR-022: Allowlist-only launch is deny-by-default + fail-closed, and the baseline blocks profile escapes
Status: Accepted
Date: 2026-06-16
Relates: ROADMAP v0.2 (child enforcement surface), ADR-020 (Day-One fail-closed baseline), ADR-021 (watchdog), DEFENSES.md (Kid §7.4, B1), docs/research/07 (B1); issue #12
Amends: ADR-020 D1 (the Day-One baseline is no longer a fixed "exactly 17" — see Decision D0)

## Context

Red-team B1 (research/07): **blocklist-as-primary loses.** A blocklist is defeated by app
clones, dual-app/parallel-space features, repackaged APKs, and — on Android 15 — a **Private
Space**, where the kid installs and runs anything in a second profile the suspend list never
touches. The allowlist-vs-blocklist precedence was also unspecified.

The child already had the *mechanism* for allowlist enforcement —
`PolicyEnforcer.applyAllowlist(Set<String>)` called `setPackagesSuspended` on the non-allowlisted
set, wired into the watchdog and the bundle applier, with a fail-closed deny-all on a
missing/corrupt bundle (ADR-021 / ATTACKS item 4). But two holes remained, and both are
enforcement-bypass surfaces (this issue is `agent-blocked`):

1. **The allowlist apply had no verify step.** Unlike `applyDayOneRestrictions` (ADR-020), it
   fired `setPackagesSuspended` and returned without reading back whether suspension stuck. A
   non-allowlisted *user* app that resists suspension (the exact clone/repackage threat) was a
   silent fail-OPEN — it stayed launchable with no signal.
2. **Nothing blocked the profile escape.** A kid could create a managed (work) profile or, on
   Android 15, a Private Space, and run un-suspended apps inside it. Deny-by-default on the
   primary profile is worthless if a second profile is a one-tap bypass.

## Options

- **A. Keep best-effort suspend, log failures.** Rejected — fail-OPEN by construction for the
  precise app (clone/repackage) the allowlist exists to stop.
- **B. Verify-or-throw the allowlist the same way ADR-020 verifies restrictions, escalate
  suspension to hiding, and block profile creation in the always-on baseline (chosen).** Reuses
  the proven Day-One fail-closed shape; closes both holes.
- **C. Block profiles only when an allowlist bundle is present.** Rejected — a Private Space can
  be created in the window *before* any bundle arrives (first boot, post-wipe). Profile blocking
  must be always-on, not gated on policy state.

## Decision

**D0 — The Day-One baseline is now API-aware, not a fixed count (amends ADR-020 D1).** ADR-020
pinned "exactly 17 user restrictions." This ADR adds the **profile-escape block** to the
always-on baseline (`PolicyEnforcer.requiredRestrictions`), so it is enforced and verified from
first boot, before any allowlist exists:

- `DISALLOW_ADD_MANAGED_PROFILE` — always (API 21+). Blocks the work-profile escape.
- `DISALLOW_ADD_PRIVATE_PROFILE` — **API 35+ only** (Android 15 Private Space). It is
  runtime-conditional: applying an unknown restriction key on API < 35 would never read back set,
  and that gap would trip ADR-020's fail-closed `lockNow()` on every boot — i.e. brick a pre-15
  device. So the *required set itself* is computed per OS level, and the verify contract is
  unchanged in spirit: **"the whole list for this OS verifiably set, or throw."** The baseline is
  therefore 18 on API ≤ 34 and 19 on API ≥ 35. (The hidden/new key is pinned by its stable AOSP
  string `"no_add_private_profile"`, mirroring how ADR-020 pins `DISALLOW_OEM_UNLOCK`.)

**D1 — Allowlist apply is deny-by-default and fail-closed (verify-or-throw).**
`applyAllowlist(allowlist)`:
1. Computes the **exempt** set = system apps + self + the active default launcher. These are
   never suspended (doing so bricks the device) and are not the threat surface; the threat is
   arbitrary user-installed / cloned / sideloaded packages.
2. Un-suspends + un-hides allowlisted apps (idempotent — a now-allowed app must launch again).
3. Suspends every non-allowlisted, non-exempt user app. Whatever `setPackagesSuspended` reports
   it could not suspend is **escalated to `setApplicationHidden(true)`** — strictly stronger
   (the app leaves the launcher entirely). If the suspend call throws wholesale, every deny
   target is treated as un-contained so the escalation + verify still runs (fail toward *more*
   restriction).
4. **Reads back** the launch-blocked state (suspended OR hidden) for each deny target. Any deny
   target still launchable is a fail-OPEN gap → `lockNow()` containment, then throw
   `AllowlistEnforcementException(stillLaunchable)`. This is the ADR-020 shape applied to launch
   policy: it never returns while a non-allowlisted app can still launch.

**D2 — Watchdog gains a profile-escape detection + containment backstop.** ADR-021's watchdog
re-asserts restrictions/allowlist/DNS each tick; it now also runs `ProfileGuard.check()` last. The
guard compares the current profile count to the baseline (1 = primary user only) and, on an
increase, **logs a containment warning and calls `lockNow()`** — the same fail-closed response
every other surface uses on a gap. A live second profile is a *full allowlist bypass* (anything
runs inside it), so detection is treated as fail-closed-with-lock, not log-only; the asymmetry of
"detect but don't contain" would be failing open relative to the restriction and allowlist
surfaces. The *creation* block is the D0 restriction; this guard catches a profile that exists
anyway (pre-existing, or a narrow window before the restriction stuck) and contains it. It locks
on **every** tick the extra profile persists (the device stays contained while compromised);
recovery is the parent removing the profile.

**D3 — Honest scope: block + detect + lock now; remove + parent-alert deferred.** There is no
event log or parent transport yet (the chain floor is a stub), so the acceptance's "blocked/**
alerted**" is met as: creation is **blocked** by the restriction, and an extra profile is
**detected, logged, and contained with `lockNow()`** locally on each tick. A parent-facing alert
is deferred to the event log — the same staged honesty as ADR-020 D4 ("FRP implemented, not yet
wired"). We also do **not** attempt to *remove* a rogue profile via DPM: Private Space removal
semantics under Device Owner are unverified and potentially destructive; deferred until validated
on a real Android 15 device. Containment (lock) is the v1 response; removal + alert come later.

**D4 — Accepted residuals.**

- **A non-allowlisted *default launcher*.** The exempt set includes the *active* default launcher
  so the home screen can't be bricked. If the kid has set a non-allowlisted third-party launcher
  as default, it stays usable. Forcing the stock launcher is DEFENSES Kid §3.4, tracked
  separately — not part of this deny-by-default change.
- **All `FLAG_SYSTEM` apps are exempt from the launch allowlist (the load-bearing one).** Suspending
  system components bricks the device and the B1 threat (clones / sideload / dual-app) is never
  `FLAG_SYSTEM`, so the exempt set takes the whole system partition. On a **Tier 1** device
  (ADR-001 — Pixel-class, AOSP+Google-clean image) this is clean: the system apps present are
  either meant to be available or governed by a *different* layer — Chrome by the managed
  `URLBlocklist` policy (ADR-009 / #16), Play by install-approval, web egress by the fail-closed
  DNS floor (ADR-016 / #19). **But ADR-001 also commits to Tier 2/3 (Samsung / OnePlus / Xiaomi /
  Motorola), which ship preloaded `FLAG_SYSTEM` apps the allowlist therefore can't suspend — e.g.
  Samsung Internet, Galaxy Store.** On those devices a kid can launch the OEM-preloaded browser/
  store past the allowlist. This is **mitigated, not closed**: the DNS floor still filters that
  browser's web egress, and install-approval still gates the store — but the *launch* of an
  un-allowlisted preloaded app is a real residual on Tier 2/3. Closing it (a per-OEM curated
  system sub-allowlist, or bloat suspension) is a **follow-up hardening item, and is exactly the
  enforcement-strength gap that motivates reassessing ADR-001's tier guarantees** — tracked
  separately, not silently accepted here. Codex and the dual adversarial review both confirmed
  "exempt all system apps" as the correct v1 posture (non-bricking; DNS covers the worst case) on
  the explicit condition that this residual is documented, which it now is.

## Consequences

Good:
- The allowlist is now genuinely fail-closed: a non-allowlisted app is suspended, escalated to
  hidden if it resists, and if it is *still* launchable the device locks and the apply throws.
  The previous silent fail-OPEN on a clone/repackage is closed.
- The Private Space / managed-profile one-tap bypass is blocked from first boot (API-aware), and a
  profile that appears anyway is detected **and contained with `lockNow()`** by the watchdog.
- Reuses ADR-020's verify-or-throw + `lockNow` idiom and ADR-021's watchdog seam, so the new
  surfaces inherit the same deterministic, seam-injected test coverage.
- **Read-error paths fail closed too (Codex-review hardening).** A failure to *enumerate* installed
  packages (`applyAllowlist`) or to *read the profile count* (`ProfileGuard`) now locks the device
  rather than silently skipping — closing two fail-OPEN-on-read-error paths the review caught (the
  watchdog would otherwise swallow the bare throw and leave the prior state usable). The
  containment lock is routed through an injected seam so the lock half of the contract is tested.

Bad / accepted limits:
- `setPackagesSuspended` / `setApplicationHidden` round-tripping depends on the platform; under
  Robolectric the shadow may not track them, so the fail-closed contract is proven via the
  injected `installedApps` + `isLaunchBlocked` seams, and instrumented deny coverage rides on the
  `connectedAndroidTest` harness (#30) — not vacuous unit assertions.
- **Post-install drift window.** The deny set is computed from the installed-package list at apply
  time, so an app installed *after* the last apply is launchable until the next watchdog tick
  re-runs `applyAllowlist`. This is the **same ≤`INTERVAL_MS` (30 s) drift bound ADR-021 already
  accepted**, further throttled by `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` + install-approval.
  The deny-by-default guarantee is therefore "≤30 s after install," not instantaneous.
- **The deprecated `DISALLOW_ADD_MANAGED_PROFILE` readback is asserted, not yet hardware-proven.**
  The verify-or-throw contract assumes a Device Owner setting that (Java-`@Deprecated`) key still
  records and reads back the restriction bit. If that assumption were wrong on some OS, verify
  would *false-trip and brick* (fail-closed direction, but still an incident). The Robolectric
  suite tops out below API 35 and only round-trips `DISALLOW_FACTORY_RESET`, so this assumption —
  and the API-35 private-profile branch — **must be covered by the `connectedAndroidTest` harness
  (#30) before this is trusted on hardware.**
- Profile-escape handling is **block + detect + lock** only until the event log exists (D3); no
  parent alert yet, and no active removal of a pre-existing rogue profile (D3).
- A non-allowlisted third-party *default launcher* stays usable (D4), and on Tier 2/3 OEM hardware
  preloaded `FLAG_SYSTEM` apps stay launchable past the allowlist (D4) — mitigated by the DNS
  floor, not closed; a follow-up hardening item tied to the ADR-001 tier reassessment.
- The baseline count is now OS-dependent (18 / 19), which is a small added test-matrix cost
  (the witness test is split by `@Config(sdk=...)`).
