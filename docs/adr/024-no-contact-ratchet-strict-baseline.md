# ADR-024: Progressive no-contact ratchet to the strict baseline + signed Heartbeat (implements ADR-017 §7)
Status: Accepted
Date: 2026-06-16
Implements: **ADR-017 §7** (ratchet toward strict baseline after N hours no-contact) + **ADR-017 §5** (monotonic-clock freshness model)
Relates: ADR-020 (strict Day-One baseline, verify-or-throw), ADR-021 (policy watchdog triggers), ADR-016 (fail-closed DNS floor), ADR-022 (deny-all allowlist); research/07 **O1** (offline freeze); docs/DEFENSES.md #8 (heartbeat + silence alarms); docs/ATTACKS.md silence ladder; issue #18

## Context

**Red-team O1 (research/07):** a kid stays offline to *freeze* the last permissive bundle until its `not_after`; nothing forces a pending more-restrictive update to win while offline, and LAN-sync starvation (firewall the parent IP / mDNS-spoof) is the same gap. ADR-017 §7 named the fix — *"after N hours with no parent contact the child ratchets toward the strict baseline"* — and §5 fixed the clock model (kernel-monotonic `elapsedRealtime()` + a signed parent re-anchor; a rolled-back anchor is an anomaly ⇒ strict baseline). **This ADR specifies the concrete mechanics ADR-017 §7 left open** and is the implementation record for issue #18.

The child code as it stands (verified):

- **No notion of "last parent contact" is persisted.** Bundle timestamps exist in `SignedBundle` but nothing records *when* an authenticated parent interaction happened.
- **Zero time-reading anywhere in child logic** — no `System.currentTimeMillis`, no `SystemClock`, no `Instant`. The ratchet introduces the child's *first* time dependency, so **clock-tamper is the central fail-closed concern**: a kid with physical access can set the device clock freely.
- **`not_after` is not enforced** by `PolicyAdmission` today. O1's ratchet is **independent of `not_after`** by design, so closing O1 does not require freshness enforcement first (that is a separate follow-up — see D7).
- The **only authenticated parent contact that exists** is a signed-bundle apply (`PolicyAdmission.admit` → `Accept`). To let a parent keep a child fresh *without* re-issuing policy, we add a minimal **signed Heartbeat** (D4).
- The **strict baseline** is already expressible: deny-all allowlist (`applyAllowlist(emptySet())`, ADR-022) + the full Day-One restriction set (`applyDayOneRestrictions`, verify-or-lock, ADR-020) + the pinned DNS floor (ADR-016). The watchdog (`PolicyWatchdog.reassert`, ADR-021) already re-asserts these every 30 s / boot / connectivity. The ratchet rides on that loop.

## Options

- **A. Single cliff — at N hours jump straight to strict baseline.** Simplest, but blunt: a parent legitimately away >N hours trips full lockdown in one step. Rejected as the default.
- **B. Aggressive — deny-all early, then `lockNow()` the device.** Tightest, but bricks the kid out of calls/system during a long *legitimate* silence. Rejected for v1.
- **C. Two-step progressive ladder — STALE (deny-all allowlist) → STRICT (deny-all + full Day-One re-verify + DNS), reset on authenticated contact (chosen).** Tolerates a normal parent-away day, "steps toward strict baseline as silence grows" per the issue, reaches strict, and never bricks the device out of system/dialer. Aligns with the 24 h top of the silence-alarm ladder (DEFENSES #8).

## Decision

Adopt **option C**. Seven parts; all fail-closed.

**D1 — Two-step ratchet tiers, driven by measured silence.** A pure function maps silence duration → tier:

| Tier | Trigger | Enforcement |
|---|---|---|
| `FRESH` | silence < `RATCHET_STALE_MS` (**24 h**) | the active bundle's own policy (status quo) |
| `STALE` | `RATCHET_STALE_MS` ≤ silence < `RATCHET_STRICT_MS` (**48 h**) | **override the allowlist to deny-all** (only the ADR-022 system-exempt set launches). The frozen bundle is still *trusted* for its DNS resolver; Day-One stays asserted. Kid's apps stop launching; device still works for system/dialer. |
| `STRICT` | silence ≥ `RATCHET_STRICT_MS` (**48 h**) | **distrust the frozen bundle entirely** → deny-all allowlist **+ ignore the bundle's `private_dns`, pinning the default filtering resolver** (DNS floor's Missing/Corrupt path) **+ full Day-One re-verify (verify-or-lock).** The hard lockdown floor; the stale permissive bundle no longer influences any surface. |

The incremental lever between the rungs is *how much of the frozen bundle is still trusted*: `STALE` stops trusting its **allowlist** (apps off) but keeps its **DNS resolver**; `STRICT` stops trusting **all** of it (allowlist + DNS) and falls to the hard default floor — the same surfaces a `Missing`/`Corrupt` bundle yields.

> **Honest scope of the lever (H2, review):** with today's enforcer the *only* ratcheted surfaces are the **allowlist** (deny-all) and the **DNS resolver** (default at STRICT). **Time-window enforcement is NOT implemented** — `PolicyEnforcer` does not read `policy.windows` at all — so "trust the bundle's windows" is currently vacuous and is deliberately omitted from the rungs above. Day-One restrictions are re-asserted every tick regardless of tier (ADR-021), so they are not a ratchet lever either. Like `not_after` (D7), window enforcement is a separate future surface; when it lands it can join the STRICT distrust set. Finer-than-two-rung gradations are future work (see Consequences).

`RATCHET_STALE_MS` / `RATCHET_STRICT_MS` are named constants (tunable). The tier function is pure → deterministically unit-testable (the issue's "clock-driven ratchet test").

**D2 — Fail-closed monotonic clock model ("when in doubt, tighten").** Silence is computed without trusting the wall clock:

- Within one boot session, silence = `elapsedRealtime() now − elapsedRealtime at last contact` — kernel-monotonic, **not user-settable**, the authoritative measure (ADR-017 §5).
- `elapsedRealtime()` resets on reboot, so across a reboot (detected: `elapsedRealtime now < stored elapsed-at-contact`) we fall back to a wall-clock delta `currentTimeMillis now − wall-at-contact`, **gated by a persisted wall-clock high-water**.
- **Any anomaly ⇒ silence is treated as ≥ `RATCHET_STRICT_MS` ⇒ strict baseline:** wall clock reads *below* the high-water (a backward roll), `elapsedRealtime` regressed with no reboot evidence, contact markers missing, or a provisioned child that has no recorded contact at all. The high-water advances to `max(seen, now)` every tick.

Net property: **clock tampering can only *accelerate* the ratchet, never delay it.** A kid who winds the clock forward trips strict sooner; one who winds it back hits the high-water anomaly and trips strict immediately. This is the fail-closed direction CLAUDE.md mandates.

**D3 — Reset only on *authenticated* parent contact.** `lastContact` (wall + elapsedRealtime pair) is recorded on, and only on, an Ed25519-verified, audience-bound (`child_device_id`) parent message:
1. a successful `PolicyAdmission.admit` → `Accept` (signed bundle applied), and
2. a verified `SignedHeartbeat` (D4).

No unauthenticated event resets the timer — not LAN visibility, not an unauthenticated `/state` poll (the REST auth layer is still a stub; relying on it would be fail-open). Authenticated = verified against the **pinned** parent key.

**D4 — `SignedHeartbeat`: minimal authenticated keep-alive.** Schema (signed body, JCS-canonical, `sig` excluded — identical rules to `SignedBundle`):

```
SignedHeartbeat { v:1, child_device_id:string, issued_at:u53-ms, sig:hex-ed25519 }
```

Child verification, fail-closed (any failure ⇒ no reset, no state change):
1. `v == 1` else reject;
2. `issued_at` JCS-safe (`0..2^53−1`) else reject (JC1, before sig);
3. `child_device_id == my id` else reject (audience, before sig — a heartbeat for child A cannot refresh child B);
4. `Ed25519.verify(sig, canonical-body-without-sig, pinnedParentPubkey)` else reject;
5. **replay floor:** `issued_at > lastHeartbeatIssuedAt` else reject — a captured heartbeat **replayed** must not reset the ratchet. The monotonic `issued_at` high-water is persisted and advanced on accept.

A new `POST /heartbeat` endpoint runs this. `issued_at` is used **only** as the replay floor — it is *not* used as the clock authority for silence (D2 uses the child's own monotonic clock), so a parent-time rollback cannot revive freshness. The **parent sender** of heartbeats is parent-kmp work (D8); this PR implements the child receipt + verification + reset + the shared schema.

**D5 — Watchdog integration: the existing allowlist + DNS seams become tier-aware** (no new re-applying step, so the allowlist never flip-flops within a tick). Each `reassert()` (30 s / boot / connectivity) the watchdog evaluates the current tier once via the injected contact clock and feeds it to the two seams it already owns:
- **allowlist source:** `FRESH` → the bundle's allowlist; `STALE`/`STRICT` → **deny-all** (`emptySet()`).
- **DNS source:** `FRESH`/`STALE` → the bundle's `private_dns`; `STRICT` → **`null`** (the DNS floor's default filtering resolver — the frozen bundle's resolver choice is ignored).
- **Day-One restrictions** are already re-asserted every tick unconditionally (ADR-021), so `STRICT` needs no extra restriction step — the hard floor is the union of (deny-all + always-on Day-One + default DNS).

So the ratchet engages within one 30 s interval of crossing a threshold. It stays **fail-closed-but-alive**: a throw evaluating the tier is caught and logged like any other surface (and a clock the watchdog cannot read is itself an anomaly ⇒ STRICT, D2). The contact clock + tier are injected as seams so the wiring is unit-testable without a live device.

**D6 — Persistence.** Contact + heartbeat state lives in the existing `EncryptedSharedPreferences` (`ReplayFloorStore`'s store, TEE/StrongBox-bound at rest per ADR-017 part 1): `lastContactWallMs`, `lastContactElapsedMs`, `wallHighWaterMs`, `heartbeatFloor`. Marker writes are `commit()`-checked **+ readback** (all fields) and throw on failure — the same fail-closed contract as the replay floor. A provisioned child with **no** contact marker is an anomaly ⇒ strict (D2).

Two contact-reset paths, two contracts (M1, review):
- **Heartbeat reset** (`admitHeartbeatContact`) advances the replay floor + records contact in **one** durable commit and **throws** on failure ⇒ the heartbeat is treated as not-admitted (no reset).
- **Bundle-apply reset** is **best-effort** (`runCatching` in the `/policy` handler): the policy apply is *already durable* (ADR-017 two-phase commit) and must not be unwound by a marker-write failure. A failed reset there only leaves the silence timer un-reset — i.e. the ratchet stays **tighter**, never looser — so the fail-closed direction holds even though this single write does not throw.

**D7 — `not_after` enforcement is explicitly OUT of scope.** O1's ratchet is independent of `not_after`; this PR closes O1 via the ratchet alone. Enforcing the `not_before`/`not_after` freshness window (the rest of ADR-017 §5: signed-time re-anchor, anchor-rollback anomaly) is a separate, larger follow-up and is **not** folded in here.

**D8 — Parent heartbeat sender is parent-kmp follow-up.** Without a parent that periodically sends a heartbeat (or re-sends the current signed bundle), a compliant-but-quiet parent's child will ratchet — *that is the safety behavior*, but the parent app MUST send periodic authenticated keep-alives to avoid tripping it. Tracked for parent-kmp; the child side and the wire schema land here.

## Consequences

Good:
- **Closes O1:** an offline kid can no longer coast indefinitely on a frozen permissive bundle; posture tightens to STALE then STRICT on a bounded schedule and reaches the strict baseline, independent of `not_after`. LAN-sync starvation is covered by the same mechanism (no authenticated contact = ratchet).
- **Clock-tamper is one-directional:** tampering only tightens. The first time dependency in the child is introduced fail-closed.
- **Heartbeat is replay- and audience-bound:** a captured or cross-device heartbeat cannot reset the timer.
- Reuses the watchdog loop (ADR-021), the strict baseline (ADR-020/022/016), and the JCS/Ed25519 verify primitives — no new crypto, no new enforcement surface invented.

Bad / accepted limits:
- **Whole-snapshot restore resets the markers.** A fully-rooted kid who snapshots *all* local state (contact markers + floor) and restores it offline can reset the silence clock — the same residual ADR-017 documents. ADR-017 part 2 designates the **parent** as the monotonicity anchor that catches this on next sync — but **that backstop is itself not yet built** (M2, review): `ReplayFloorStore.chainFloor()` is `null` (no append-only event-log chain) and there is no parent reconcile shipped, so today this residual is **uncaught even locally**. It narrows only when the event log + parent sync land (tracked, ADR-017 part 1/2). The ratchet still bounds the *honest* offline case (no snapshot tooling) on schedule.
- **Reboot loses `elapsedRealtime`,** so cross-reboot silence falls back to the wall-clock-vs-high-water path; this is fail-closed (anomaly ⇒ tighten) but coarser than the in-session monotonic measure.
- **A parent who sends nothing trips the ratchet.** By design — but it makes a real parent-side dependency (D8): the parent app must send periodic heartbeats/bundles.
- **Coarse steps.** With the current policy model the meaningful lever is the allowlist (deny-all) plus the Day-One floor; STALE vs STRICT is therefore a two-rung ladder, not a continuous taper. Finer gradations would need a richer policy-tier model (future).

## Test plan

- **Pure tier function:** silence below/at/above each threshold → `FRESH`/`STALE`/`STRICT` (clock-driven).
- **Fail-closed silence calc:** in-session monotonic delta; reboot fallback; wall clock below high-water ⇒ STRICT; missing markers ⇒ STRICT; provisioned-but-never-contacted ⇒ STRICT.
- **Heartbeat verify:** good sig + fresh `issued_at` ⇒ reset; bad sig / wrong audience / non-JCS `issued_at` / `v≠1` ⇒ reject (no reset); **replayed `issued_at ≤ floor` ⇒ reject** (sync-resume reset test + replay-rejection test).
- **Watchdog ratchet step:** `STALE` forces deny-all allowlist; `STRICT` forces deny-all + Day-One re-assert; never throws (fail-closed-but-alive).
- **Reset path:** a successful `admit` Accept records contact and returns the tier to `FRESH`.

## Cross-refs
- [ADR-017](017-replay-rollback-resistance.md) (§5 clock model, §7 ratchet — this implements both)
- [ADR-020](020-failclosed-dayone-restrictions.md) / [ADR-022](022-allowlist-deny-by-default-profile-escape.md) / [ADR-016](016-fail-closed-dns-floor.md) (the strict-baseline surfaces)
- [ADR-021](021-policy-watchdog-reassert-triggers.md) (the watchdog loop the ratchet rides)
- docs/research/07-redteam-design-review.md (O1); docs/DEFENSES.md #8; docs/ATTACKS.md (silence ladder)
- CLAUDE.md (fail-closed; replay protection mandatory)
