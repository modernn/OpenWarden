# ADR-041: Child enforces the policy-bundle freshness window via a §5.1 monotonic anchor

Status: Accepted
Date: 2026-06-23
Relates: ADR-034 (residual #90 discharged here), ADR-017 (replay floor), ADR-024 (no-contact ratchet — reuses its tier + clock infra), ADR-040 (#91 verify-over-bytes, sibling residual). PROTOCOL §2.1 steps 9-11, §5, §5.1.

## Context

The parent signs `not_before`/`not_after` on every bundle (#27), but the child `PolicyAdmission`
never evaluates them — there is no `CLOCK_SKEW`/`EXPIRED` step (PROTOCOL §2.1 steps 9-10). So the
freshness window is **inert end-to-end**: a signed bundle is admissible forever, re-widening the
replay surface PROTOCOL §5 / ATTACKS H1/C8 were written to close. This is the maintainer-accepted
ADR-034 residual tracked as #90.

The hard part is the clock. The kid controls the device wall clock (`DISALLOW_CONFIG_DATE_TIME` is
**not** a day-one restriction), so `System.currentTimeMillis()` cannot be the time source. PROTOCOL
§5.1 mandates a **monotonic estimate** anchored to a *signed parent timestamp*:

    monotonic_now_ms = parent_anchor + (elapsedRealtime_now − elapsedRealtime_at_anchor)

where `parent_anchor` is re-established only by a signed parent time — `bundle.issued_at` at apply
time, or `Heartbeat.issued_at`. `SystemClock.elapsedRealtime()` is kernel-monotonic and survives
wall-clock edits, but **resets to 0 on reboot**, so a persisted `(parent_anchor, elapsed_at_anchor)`
pair is meaningless after a reboot and must be detected and discarded.

A read of the existing code shows most of the machinery already exists: `ReplayFloorStore`
persists contact markers + a wall high-water with the same fail-closed commit+readback contract;
`Ratchet`/`ContactClock` already detect `elapsedRealtime` regression (reboot/tamper) and fail toward
strict; `PolicyWatchdog` already drops to deny-all + default-DNS at the STALE/STRICT tier (ADR-024).
This ADR reuses all of it.

## Decision

**D1 — Persist a signed-parent-time anchor.** Add three fail-closed (`commit()`+readback) fields to
`ReplayFloorStore` (a new `FreshnessAnchorStore` capability): `anchor_parent_ms` (the `issued_at` of
the last *applied* bundle / *admitted* heartbeat — a signed parent time), `anchor_elapsed_ms`
(`elapsedRealtime()` captured at that same instant), and `not_after_watermark_ms` (the highest
`not_after` ever applied). The anchor is re-established ONLY from a verified signed parent time.

**D2 — Pure clock estimator (`FreshnessClock`).** `estimate(anchor, nowElapsedMs) -> Now` returns
`Usable(monotonic_now_ms)` only when an anchor exists AND `nowElapsedMs >= anchor_elapsed_ms`
(same boot, no regression); otherwise `Unusable` (no anchor yet, or reboot/elapsed-regression).
Never fabricate a time from the wall clock. Pure, no Android — fully host-testable.

**D3 — Admission window check (pure `decide`, steps 9-11).** `decide()` gains an injected
`freshnessNow: FreshnessClock.Now` and `notAfterWatermarkMs: Long?`, evaluated AFTER signature +
floor pass, BEFORE `Accept`:
- step 9 — `Usable` && `monotonic_now < not_before` → **`Defer`** (CLOCK_SKEW): do NOT apply, keep
  the current policy, retry on the next contact. A distinct outcome from a rejection.
- step 10 — `Usable` && `monotonic_now >= not_after` → **`RejectExpired`** (EXPIRED): do NOT apply;
  caller drops to / stays in the stale baseline.
- step 11 — anchor/watermark **monotonic-on-write** (D4): the persisted `parent_anchor` and
  `not_after_watermark` never decrease — the child cannot lower its own anchor, so a *local* anchor
  edit to revive an expired bundle fails closed. **Full snapshot-revival detection** (stored anchor
  read *lower* than an independent witness — F1/F2/F3) needs a second witness, exactly like the
  replay floor's not-yet-built chain mirror (`ReplayFloorStore.chainFloor()` == null today); it is
  the **same tracked gap**, caught by the parent on next sync (ADR-017 part 1/2), not yet locally.
  The watermark is persisted now so that detection can wire in with the chain mirror. The concrete
  local defenses shipped here are steps 9/10 + monotonic-on-write + elapsed-regression (reboot).
- `Unusable` anchor (genesis, or post-reboot before re-anchor): the window **cannot** be evaluated.
  At **admission** the candidate is admitted on its signature + monotonic `policy_seq` floor alone and
  **re-seeds** the anchor from its own `issued_at` on apply (D4) — the recovery path (a parent push
  re-anchors the clock). At the **watchdog** (D5) an `Unusable` clock forces **`STALE` (deny-all)**:
  we cannot confirm the active bundle is still in its window, so fail-closed. Genesis self-anchoring
  happens only AFTER signature verification (ADR-040 admit ordering).

**D4 — Anchor seeding is post-apply and never from the candidate under test.** A non-genesis
freshness check uses the anchor from a *previously* applied bundle/heartbeat — never the bundle being
evaluated (that would make steps 9-10 self-satisfying). The anchor advances to the candidate's
`issued_at` (and the watermark to its `not_after`) only inside the two-phase commit, AFTER durable
apply, monotonically (never backward).

**D5 — Active-bundle freshness folds into the watchdog tier (continuous enforcement).** The window
must also bite an *already-applied* bundle as time passes, not only at admission. `PolicyWatchdog`
computes a freshness tier from the active bundle's `not_after` vs `FreshnessClock.estimate`:
- a **`Usable` estimate past `not_after` → `STALE`** (deny-all allowlist + default DNS): the applied
  policy has expired (PROTOCOL §5 step 10);
- an **`Unusable` clock → `STALE`** (deny-all): **fail-closed** — the clock cannot confirm the active
  bundle is still within its window. This is **load-bearing**: without it a bundle that already
  EXPIRED (and was denied-all) would **un-expire on reboot** (`elapsedRealtime` resets → `Unusable` →
  its allowlist returns), a kid regaining apps by rebooting (caught by the Codex review pass — an
  earlier "defer to the ratchet" draft was a fail-OPEN). The deny-all is **brief**: the next
  authenticated parent push / heartbeat re-anchors the clock and the allowlist returns. "Restrictions
  intact after restart" holds — deny-all is *more* restriction, not less; the cost is a short
  post-reboot allowlist suspension until the next parent contact (the accepted fail-closed trade).

The effective tier is `max(ratchetTier, freshnessTier)` — reusing the existing ADR-024 deny-all path;
freshness can only *tighten*, never loosen.

**D6 — Heartbeat re-anchors.** `admitHeartbeatContact` already carries `hb.issued_at`; it now also
advances the freshness anchor (issued_at + elapsed), so a heartbeat re-establishes a `Usable` clock
without a full bundle. (Near-free; closes the post-reboot stale gap quickly.)

**D7 — Outcome taxonomy.** Add `Defer` (no-apply, keep current, retry) and `RejectExpired`
(no-apply, stale baseline) as distinct `PolicyAdmission.Outcome`/`Result` variants from
`RejectStrict`, because their caller behavior differs (Codex/crypto review). The wire ack reason
codes `CLOCK_SKEW` / `EXPIRED` already exist in PROTOCOL.

## Consequences

- The 24h freshness window becomes live end-to-end; a captured/old or future-dated bundle is no
  longer admissible outside its signed window, and an applied bundle expires into the stale baseline
  at `not_after` (tightened from the ADR-024 24h no-contact ratchet, which remains the backstop).
- Fail-closed throughout: every ambiguous clock state (`Unusable`, rollback, missing anchor) yields
  *more* restriction (stale/strict), never less; clock tampering can only accelerate restriction.
- Reboot is handled by elapsed-regression detection (reuses the `Ratchet` pattern): the anchor goes
  `Unusable` → watchdog holds stale baseline → first signed bundle/heartbeat re-anchors. Restrictions
  stay in force across reboot (stale = restrictions), so the "Day-One restrictions across reboot"
  property is preserved.
- No `proto`/wire change; no new crypto primitive. Reuses `ReplayFloorStore`, `Ratchet`,
  `ContactClock`, `PolicyWatchdog`.
- **Disclosed residuals (tracked, maintainer-accepted):**
  - Full snapshot-revival detection (step 11 read-time anchor/watermark-rollback vs an independent
    witness) is deferred to the same follow-up as the replay-floor chain mirror (`chainFloor()`);
    the local defenses here are steps 9/10 + monotonic-on-write + reboot detection, with the whole-
    snapshot case caught by the parent on next sync (ADR-017 part 1/2).
  - The PROTOCOL §5.1 ">24h wall-clock vs monotonic divergence → stale" secondary tripwire is tracked
    as a follow-up (the primary anchor/reboot/expiry handling is implemented).
  - **HARD pre-prod gate:** the pure decision logic (`FreshnessClock`, `freshnessGate`, `freshnessTier`,
    `nextAnchor`) is host-tested deterministically, but `ReplayFloorStore.advanceFreshnessAnchor`'s
    `EncryptedSharedPreferences` round-trip (commit+readback durability across process restart, and
    `elapsedRealtime` reboot behavior) is **device-only** — it needs an instrumented `connectedAndroidTest`
    before production, matching the same gate accepted for other `ReplayFloorStore`-backed state (e.g. #98).
  - **Post-reboot admission applies a new bundle without a window check** (crypto-review MED-1): under an
    `Unusable` anchor `decide()` admits a signed, audience-bound, above-floor bundle and re-anchors from
    its own `issued_at`. A captured **higher-seq-but-expired** bundle could thus be (re)applied once
    post-reboot and self-anchor as "fresh". Bounded: it must out-rank the `policy_seq` floor (so the
    parent must have gone silent — no newer bundle advanced the floor), and that same parent silence
    drives the ADR-024 ratchet to deny-all; physical capture is required. Accepted residual, not a
    blocker — the admission path favors recovery (a real parent push must take effect post-reboot).
  - **Stale-policy realized as deny-all, not the §5 "essentials-only allowlist"** (crypto-review MED-2):
    expiry routes to the ADR-024 `STALE` tier = empty allowlist (+ the ADR-022 enforcer-layer system
    exemptions), which is *stricter* than PROTOCOL §5 item 3's "essentials only (dialer, parent app,
    school app)" and so satisfies fail-closed. PROTOCOL §5 item 3 wording should be reconciled with the
    deny-all realization so no future implementer reads it literally and *loosens* to a hand-rolled
    essentials allowlist (maintainer/doc follow-up).
  - **Couple the two deferred witnesses** (crypto-review LOW-1): the freshness step-11 anchor/watermark
    rollback witness and the replay-floor chain mirror (`chainFloor()`) are the *same* whole-snapshot
    gap and MUST land together — the chain-mirror follow-up must read the highest `AckPolicy` **and**
    validate the `not_after` watermark, or freshness rollback detection is silently forgotten.

## References
ADR-034, ADR-017, ADR-024, ADR-040; PROTOCOL §2.1 (steps 9-11), §5, §5.1; ATTACKS H1/C8/F1/F2/F3;
`child-android/.../{PolicyAdmission,ReplayFloorStore,ContactClock,Ratchet,PolicyWatchdog}.kt`; issue #90.
