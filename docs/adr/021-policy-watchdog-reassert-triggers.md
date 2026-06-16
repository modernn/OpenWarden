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

**D1 — One re-assert path: `PolicyWatchdog.reassert()`.** It re-applies every local policy
surface — Day-One restrictions (fail-closed, ADR-020), the active bundle's allowlist, and the
DNS floor — and is **fail-closed-but-alive**: each surface is guarded independently (a failure in
one must not skip the others — re-asserting fewer surfaces is failing *open*), and the method
**never propagates**. The Day-One enforcer already calls `lockNow()` on a verify gap; the
watchdog's job is to keep retrying, so a throwing surface is logged, not fatal. The surfaces are
injected as seams so this contract is unit-testable without a live Device Owner.

**D2 — Three triggers in `PolicyService`.**
1. **Boot / service start** — `onStartCommand` (BootReceiver starts the FGS on boot).
2. **Connectivity change** — a `registerDefaultNetworkCallback` re-asserts on network
   available/lost (the DNS floor must be re-pinned on connectivity, ADR-016).
3. **Periodic timer** — a `Handler` tick every `PolicyWatchdog.INTERVAL_MS` (30 s), bounding how
   long a silently cleared restriction can persist.
`START_STICKY` remains for OS kill-restart.

**D3 — DNS-floor re-assert is a wired seam, body deferred to #19.** The connectivity/boot/timer
triggers already call `reassertDnsFloor`; only its body (pin Private DNS to the public filtering
resolver) lands with the DNS-floor issue, so no rewiring is needed then.

## Consequences

Good:
- Drift is reverted within at most one timer interval (~30 s) even with no broadcast, and
  immediately on connectivity change — closing the "service alive, restriction cleared" hole.
- The FGS survives a throwing re-assert surface (fail-closed-but-alive), so one failing surface
  cannot take the watchdog down.
- The DNS-floor reassert plumbing is ready for #19 with no further wiring.

Bad / accepted limits:
- The 30 s timer is a v1 battery-vs-latency tradeoff; a tighter interval reverts faster but costs
  more wakeups. Revisit if drift latency proves too slow.
- Full **instrumented** boot-reapply and drift-revert (clear a real restriction on a device, see
  the watchdog revert it) belong in the `connectedAndroidTest` suite (tracked with the CI/e2e
  work, #30/#31). This change covers the watchdog's fail-closed-but-alive logic deterministically
  with unit tests; the device-level revert rides on the enforcer's own readback (ADR-020).
