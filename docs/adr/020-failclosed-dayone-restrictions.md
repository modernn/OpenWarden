# ADR-020: Day-One restriction baseline is applied fail-closed (verify-or-throw)
Status: Accepted
Date: 2026-06-16
Relates: ROADMAP v0.1 (DPC foundation), DEFENSES.md (row 2), ATTACKS.md, research/07; issue #8

## Context

`PolicyEnforcer.applyDayOneRestrictions()` is the child's first-boot lockdown: it applies the
v1 user-restriction baseline as Device Owner. The original implementation looped over the
restrictions and, on a per-restriction failure, **logged the error and continued**:

```kotlin
day1.forEach {
    try { dpm.addUserRestriction(admin, it) }
    catch (e: Exception) { Log.e(TAG, "Failed to apply $it") }   // <-- swallow + continue
}
```

That is **fail-OPEN**: if any restriction fails to apply, the method returns normally and the
device is left in a partially-unrestricted state with no signal. This violates the
non-negotiable "every error path defaults to *more* restriction, never less."

Three further gaps surfaced while fixing this (issue #8, research/07):

1. The applied set (11 restrictions) did not match the canonical baseline in DEFENSES.md row 2
   (17). Missing: `OEM_UNLOCK`, `APPS_CONTROL`, `USER_SWITCH`, `MOUNT_PHYSICAL_MEDIA`,
   `CONFIG_TETHERING`, `CONFIG_MOBILE_NETWORKS`.
2. `research/07` flagged a documentation contradiction: DEFENSES.md lists
   `USER_SWITCH/ADD_USER/REMOVE_USER/OEM_UNLOCK` as **shipped**, while ATTACKS.md lists the
   same set under "restrictions to ADD (not in current scaffold)."
3. FRP (`applyFrpAccounts`) was a `TODO` stub.

## Options

- **A. Keep log-and-continue, add an alert.** Rejected — still returns in a partial state;
  fail-open by construction.
- **B. Throw on the first restriction that fails.** Rejected — stops applying the remaining
  restrictions, leaving *more* surfaces open than necessary (fails toward less restriction).
- **C. Apply all, then verify by readback, then throw on any gap (chosen).** Applies every
  restriction (fails toward *more* restriction even if one entry errors), reads the actual
  state back via `UserManager`, and throws if anything is missing. Never returns partial.

## Decision

**D1 — The canonical Day-One baseline is DEFENSES.md row 2: exactly 17 user restrictions.**
This list *is* the strict baseline; there is no relaxed variant in v1. It is pinned in
`PolicyEnforcer.requiredRestrictions` and independently re-asserted by a test witness.

**D2 — Verify-or-throw, never return partial.** `applyDayOneRestrictions()` applies the full
set, then `verifyOrThrow()` reads each restriction back via `UserManager.hasUserRestriction`
and throws `RestrictionEnforcementException(missing)` if any required restriction is not set.
On that throw it calls `DevicePolicyManager.lockNow()` as last-resort containment so a
half-locked device is not left usable. The method is idempotent — safe to call on first
provision and on every boot / watchdog tick.

**D3 — Callers re-assert and stay alive.** `AdminReceiver.onProfileProvisioningComplete` and
the `PolicyService` foreground-service tick both call `applyDayOneRestrictions()` and **catch**
the exception so the FGS watchdog keeps running and retries on the next tick. The device is
already locked by D2, so swallowing in the caller is *fail-closed-but-alive*, not fail-open.

**D4 — FRP is implemented, best-effort, and refuses to brick.** `applyFrpAccounts()` sets
`setFactoryResetProtectionPolicy` (API 30+). It refuses an empty account set (enabling FRP with
no recovery account would brick the device). Honesty caveat: FRP reliably blocks reset only on
Pixel-class hardware with a locked bootloader; on much of Tier-2 it is bypassable (research/07).
This is documented, not hidden, and is mitigated separately by heartbeat-silence alerts.

**D5 — Scope boundaries.** `DISALLOW_CONFIG_PRIVATE_DNS` is intentionally **not** in this set —
the DNS fail-closed floor (pin the resolver + lock the toggle) is owned by issue #19 so the DNS
story lives in one place. `DISALLOW_OEM_UNLOCK` is a hidden `@SystemApi` constant, so it is
pinned by its stable AOSP string key (`"no_oem_unlock"`); a Device Owner can still set it.

## Consequences

Good:
- The day-one lockdown is genuinely fail-closed: it either verifies the full baseline or locks
  the device and throws. The previous silent partial-unrestricted window is closed.
- The applied set now matches DEFENSES.md row 2; the ATTACKS.md vs DEFENSES.md contradiction
  from research/07 is reconciled in the same change (those restrictions marked shipped).
- FRP and OEM_UNLOCK are documented per-tier honestly rather than over-claimed.

Bad / accepted limits:
- Readback verification depends on `UserManager` reflecting DO-applied restrictions. This holds
  on a real device; under Robolectric the shadow may not wire `addUserRestriction → UserManager`,
  so the round-trip test is `assumeTrue`-gated (fail-or-skip, never vacuous). The fail-closed
  semantics themselves are proven deterministically via an injected readback seam.
- `OEM_UNLOCK` and FRP are best-effort on non-Pixel hardware — a real residual gap, tracked as
  an A-class attack and covered by heartbeat-silence detection rather than a hard block.
