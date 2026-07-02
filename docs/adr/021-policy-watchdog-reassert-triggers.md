# ADR-021: Policy watchdog re-asserts on boot, connectivity, and a periodic timer
Status: Accepted
Date: 2026-06-16
Relates: ADR-020 (fail-closed Day-One restrictions), ADR-016 (fail-closed DNS floor); issue #11

## Context

After ADR-020, `PolicyService` re-asserts the Day-One restriction baseline and the active-bundle
allowlist on every `onStartCommand`. That covers boot (BootReceiver starts the FGS) and OS
kill-restart (`START_STICKY`), but **not drift while the service stays alive**: if a restriction
is silently cleared and the service is not restarted, nothing reverts it until the next start.
Issue #11 asks for a self-healing watchdog that re-asserts on **boot, connectivity change, and
on a timer**, and ADR-016 requires the DNS floor to be re-pinned **on connectivity change**.

## Options

- **A. Re-assert only on service start (status quo).** Rejected — a cleared restriction persists
  until an unrelated restart; no bound on drift duration.
- **B. Event-driven only (boot + connectivity + package events).** Lighter on battery, but a
  silent restriction-clear that fires no broadcast is never reverted. Rejected as the sole
  mechanism — leaves a fail-open hole for exactly the tampering #11 targets.
- **C. Boot + connectivity + periodic timer (chosen).** Three triggers; the timer bounds drift
  duration regardless of whether any event fires.

## Decision

**D1 — One re-assert path: `PolicyWatchdog.reassert()`.** It re-applies the local policy
surfaces it owns today — the Day-One restriction baseline (fail-closed, ADR-020) and the app
allowlist — plus a **wired-but-empty DNS-floor seam** (D3). It is **fail-closed-but-alive**: each
surface is guarded independently (a failure in one must not skip the others — re-asserting fewer
surfaces is failing *open*), and the method **never propagates**. The Day-One enforcer already
calls `lockNow()` on a verify gap; the watchdog's job is to keep retrying, so a throwing surface
is logged, not fatal. The surfaces are injected as seams so this contract is unit-testable
without a live Device Owner.

**D1a — Allowlist re-assert is fail-closed on a missing/corrupt bundle.** The allowlist surface
branches on `PolicyStore.load()` (not `loadActive()`, which collapses `Missing`+`Corrupt` to
`null`): a `Loaded` bundle → its allowlist; `Missing` **or** `Corrupt` → **deny-all** suspension
(`applyAllowlist(emptySet())`). A corrupt bundle is the G2 storage-fill / tamper vector
(DEFENSES.md G2, ATTACKS.md item 4) — it must yield *more* restriction, never a frozen allowlist.

**D2 — Three triggers in `PolicyService`.**
1. **Boot / service start** — `onStartCommand` (BootReceiver starts the FGS on boot).
2. **Connectivity change** — a `registerDefaultNetworkCallback` re-asserts on network
   available/lost (the DNS floor must be re-pinned on connectivity, ADR-016).
3. **Periodic timer** — a `Handler` tick every `PolicyWatchdog.INTERVAL_MS` (30 s), bounding how
   long a silently cleared restriction can persist.
`START_STICKY` remains for OS kill-restart.

**D3 — DNS-floor re-assert is a wired seam, body deferred to #19.** The connectivity/boot/timer
triggers already call `reassertDnsFloor`; only its body (pin Private DNS to the public filtering
resolver) lands with the DNS-floor issue, so no rewiring is needed then. **Be explicit: there is
no DNS floor in the tree today** — no `setGlobalPrivateDnsMode` exists, and ADR-016 is still
`Proposed`. So the connectivity trigger is **inert for DNS until #19**, and the DNS surface is
**fail-open in the interim** (ADR-020 D5 records #19 as the blocking dependency; #19 must pin a
*public filtering* resolver and never fall back to OFF/OPPORTUNISTIC/localhost — research/07 K3).
The seam exists to avoid rewiring, not to imply ADR-016 is satisfied.

## Consequences

Good:
- Drift is **bounded** to at most one timer interval (~30 s) even with no broadcast, and
  re-asserted immediately on connectivity change. The timer/connectivity are *triggers*; the
  actual containment is the enforcer's `lockNow()`-on-verify-gap (ADR-020) that fires when the
  next tick observes the gap. The watchdog bounds the window, it does not eliminate it.
- The FGS survives a throwing re-assert surface (fail-closed-but-alive), so one failing surface
  cannot take the watchdog down.
- A missing/corrupt active bundle re-asserts **deny-all** rather than freezing the allowlist (D1a).
- The DNS-floor reassert plumbing is ready for #19 with no further wiring.

Bad / accepted limits:
- The 30 s timer is a v1 battery-vs-latency tradeoff; a tighter interval reverts faster but costs
  more wakeups. Revisit if drift latency proves too slow.
- Full **instrumented** boot-reapply and drift-revert (clear a real restriction on a device, see
  the watchdog revert it) belong in the `connectedAndroidTest` suite (tracked with the CI/e2e
  work, #30/#31). This change covers the watchdog's fail-closed-but-alive logic deterministically
  with unit tests; the device-level revert rides on the enforcer's own readback (ADR-020).

## Amendment — 2026-07-01: boot/provision → service-start wiring is now regression-tested (#75)

A red-team pass (#75) reported "PolicyService not observed running after a reboot", with
`private_dns_mode=null` and no Day-One restrictions. Investigation found this to be a
**test-methodology false negative**, not a production regression:

- The child was observed on a **non-Device-Owner** device. The DPM enforcement APIs
  (`addUserRestriction`, the DNS floor's `setGlobalPrivateDnsMode`, the allowlist) all require
  Device Owner; on a non-DO device they are no-ops/throw, so empty restrictions + null DNS is the
  *expected* platform behavior. The live watchdog was in fact ticking every `INTERVAL_MS` and
  failing closed correctly (`"Not Device Owner — cannot enforce restrictions … retry next tick"`).
- An `adb install -r` leaves the app in Android's **"stopped" state**, which suppresses
  `BOOT_COMPLETED` delivery until the first manual launch. In real provisioning the app is launched
  during OOBE (admin-enable starts the FGS), so subsequent reboots deliver the broadcast normally.

The `reassert()` semantics (order + fail-closed-but-alive) were already covered by
`PolicyWatchdogTest`, and the Day-One baseline completeness by `PolicyEnforcerTest`. The one
**untested link** was the D2-trigger-1 *chain wiring itself* — that a boot/provision broadcast
actually (re)starts the foreground service. That is now pinned deterministically on the JVM:

- `BootReceiverTest` — `BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED` each issue a `PolicyService`
  start intent (Robolectric shadow of the started service).
- `AdminReceiverTest` — `onEnabled` starts the service; `onProfileProvisioningComplete` starts it
  **even when the Day-One apply throws** (no DO), proving the `finally` keeps enforcement alive to
  retry rather than leaving it dead (fail-closed-but-alive, per ADR-020).

**Why this is a JVM contract and not an adb-driven CI gate:** a Device Owner enforcing
`DISALLOW_DEBUGGING_FEATURES` severs adb, so the device-level assertion "after a real boot the
restrictions are physically live" cannot be observed over adb in CI (the #30 criterion-2 /
`connectedAndroidTest` constraint, #124). The chain-wiring test is the deterministic home of the
"enforcement comes up on boot/provision" contract; the physical restriction readback rides
ADR-020's `lockNow()`-on-verify-gap and the out-of-band instrumented suite. Test-only change; no
enforcement behavior was modified.
