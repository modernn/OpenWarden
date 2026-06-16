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

**D2 — Watchdog gains a profile-escape detection backstop.** ADR-021's watchdog re-asserts
restrictions/allowlist/DNS each tick; it now also runs `ProfileGuard.check()` last. The guard
compares the current profile count to the baseline (1 = primary user only) and, on an increase,
logs a containment warning. The *blocking* is the D0 restriction; this is detection for the case
where a profile exists anyway (pre-existing, or a narrow window before the restriction stuck).

**D3 — Honest scope: block + detect/log now; parent alert when the event log lands.** There is no
event log or parent transport yet (the chain floor is a stub), so the acceptance's "blocked/**
alerted**" is met as: creation is **blocked** by the restriction, and an extra profile is
**detected and logged** locally with a re-assert on the same tick. A parent-facing alert is
deferred to the event log — the same staged honesty as ADR-020 D4 ("FRP implemented, not yet
wired"). We do **not** attempt to *remove* a rogue profile via DPM: Private Space removal
semantics under Device Owner are unverified and potentially destructive; deferred until validated
on a real Android 15 device.

**D4 — Accepted residual: a non-allowlisted *default launcher*.** The exempt set includes the
*active* default launcher so the home screen can't be bricked. If the kid has set a
non-allowlisted third-party launcher as default, it stays usable. Forcing the stock launcher is
DEFENSES Kid §3.4, tracked separately — not part of this deny-by-default change.

## Consequences

Good:
- The allowlist is now genuinely fail-closed: a non-allowlisted app is suspended, escalated to
  hidden if it resists, and if it is *still* launchable the device locks and the apply throws.
  The previous silent fail-OPEN on a clone/repackage is closed.
- The Private Space / managed-profile one-tap bypass is blocked from first boot (API-aware), and
  a profile that appears anyway is detected by the watchdog.
- Reuses ADR-020's verify-or-throw + `lockNow` idiom and ADR-021's watchdog seam, so the new
  surfaces inherit the same deterministic, seam-injected test coverage.

Bad / accepted limits:
- `setPackagesSuspended` / `setApplicationHidden` round-tripping depends on the platform; under
  Robolectric the shadow may not track them, so the fail-closed contract is proven via the
  injected `installedApps` + `isLaunchBlocked` seams, and instrumented deny coverage rides on the
  `connectedAndroidTest` harness (#30) — not vacuous unit assertions.
- Profile-escape handling is **block + local-log** only until the event log exists (D3); no
  parent alert yet, and no active removal of a pre-existing rogue profile (D3).
- A non-allowlisted third-party *default launcher* stays usable (D4), tracked under Kid §3.4.
- The baseline count is now OS-dependent (18 / 19), which is a small added test-matrix cost
  (the witness test is split by `@Config(sdk=...)`).
