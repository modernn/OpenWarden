# ADR-017: Replay/rollback resistance — parent-anchored, fail-closed, JCS integer bound

Status: Proposed
Date: 2026-06-16

## Context

A red-team review of the replay/rollback story found three issues that defeat the
CRITICAL replay invariant in [`CLAUDE.md`](../../CLAUDE.md) ("Replay protection
mandatory (`policy_seq` monotonic + freshness window)"). The current design
*detects* rollback after the fact; it does not *prevent* it, and the floor it
relies on is poisonable. A subsequent cryptographer's second-opinion review then
found that the first proposed fix rested on an **Android API that does not exist**
and shipped a crash-unsafe commit ordering. This ADR supersedes that draft.

- **K1 — the replay floor is not rollback-resistant (detection ≠ prevention).**
  [`PROTOCOL.md:280`](../PROTOCOL.md) and [`PROTOCOL.md:147`](../PROTOCOL.md)
  reject any bundle with `policy_seq ≤ last_applied_policy_seq`. But the floor
  itself, `last_applied_policy_seq`, is persisted in child-controlled storage:
  [`CRYPTO.md:341`](../CRYPTO.md) ("`policy_seq` and `not_after` watermarks
  (child)") and [`CRYPTO.md:339`](../CRYPTO.md) ("Last-seen `cmd_seq` (child)")
  live in **encrypted DataStore** ([`CRYPTO.md:336`](../CRYPTO.md), "encrypted
  DataStore on Android with StrongBox-backed master keys"). Encrypting the file
  does not make the counter monotonic — it only protects confidentiality. An
  attacker with root, or anyone able to act during a wipe/reprovision window,
  restores an older DataStore snapshot, the floor rolls **back**, and an older,
  more-permissive *validly signed* bundle re-applies as "fresh" (passes
  [`PROTOCOL.md:147`](../PROTOCOL.md) step 5, signature step 4, and the freshness
  window if its `not_after` is still open). The doc only promises POST-HOC
  detection — [`CRYPTO.md:352`](../CRYPTO.md): "Counters (kid can read; rolling
  them back is detected by parent's monotonicity check on next sync)." That is
  parent-side detection on the *next* sync, which may be days away on an
  offline-tolerant device; the permissive policy is live in the interim. This
  violates the [`CLAUDE.md`](../../CLAUDE.md) fail-closed invariant.

- **K2 — two counter names, and the floor is keyed by parent pubkey (replay-after-rotate).**
  [`PROTOCOL.md:125`](../PROTOCOL.md) and [`PROTOCOL.md:280`](../PROTOCOL.md) name
  the counter `policy_seq` and gate on `last_applied_policy_seq`. But
  [`CRYPTO.md:233`](../CRYPTO.md) (`SignedBundle.cmd_seq`),
  [`CRYPTO.md:250`](../CRYPTO.md) (`bundle.cmd_seq <= lastSeenSeq(parentEd)`), and
  [`CRYPTO.md:262`](../CRYPTO.md) describe a *different* counter, `cmd_seq`, keyed
  by `(parent_ed25519_pub, cmd_seq)`. Two problems: (a) the names are not unified,
  so an implementer can satisfy one doc and miss the other; (b) keying the floor
  by *parent pubkey* means key **rotation** ([`CRYPTO.md:288`](../CRYPTO.md)
  `RotateKey`, [`PROTOCOL.md:417`](../PROTOCOL.md)) installs a *new* pubkey for
  which `lastSeenSeq(newEd)` reads zero — the floor resets, and every bundle the
  old key ever signed can replay under the new key. A rotation must never lower
  the floor.

- **JC1 — u64 counters/timestamps are not JCS-safe (round-trip failure + floor-poison DoS).**
  [`PROTOCOL.md:36`](../PROTOCOL.md) (`seq` u64),
  [`PROTOCOL.md:125`](../PROTOCOL.md) (`policy_seq` u64),
  [`PROTOCOL.md:38`](../PROTOCOL.md)/[`:126`–`:128`](../PROTOCOL.md) (`issued_at`,
  `not_before`, `not_after` u64) all declare 64-bit integers. But
  [`PROTOCOL.md:170`](../PROTOCOL.md) (§3.1 rule 4) mandates RFC 8785 §3.2.2.3
  number serialization, which is **ECMAScript `Number.prototype.toString`** — an
  IEEE-754 double, exact only to 2^53−1. Any `policy_seq` (or timestamp) above
  2^53−1 cannot round-trip: the signer's canonical bytes and the verifier's
  canonical bytes diverge, so a legitimately signed bundle fails `SIG_FAIL`
  ([`PROTOCOL.md:146`](../PROTOCOL.md)) — and worse, an attacker who lands one
  bundle with `policy_seq` near 2^63 sets the floor so high that **no future
  bundle can ever exceed it** ([`PROTOCOL.md:147`](../PROTOCOL.md) rejects them
  all as `REGRESSION`): a permanent update-freeze DoS. §1.1's "u64" and §3.1
  rule 4's "JCS numbers" are internally inconsistent.

### Reality check that killed the first fix (cryptographer's second opinion)

The first draft of this ADR proposed holding `last_applied_policy_seq` in a
"hardware rollback-resistant monotonic counter" created with
`KeyGenParameterSpec.Builder.setRollbackResistant(true)`. **That API does not
exist.** The public
[`android.security.keystore.KeyGenParameterSpec.Builder`](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)
exposes `setIsStrongBoxBacked(boolean)` and `setMaxUsageCount(int)` (a usage
*countdown* that decrements toward zero and disables the key — **not** an
app-readable/app-settable monotonic counter you can park a `policy_seq` floor
in), and nothing else resembling a rollback-resistant counter. KeyMint/Keymaster
HAL *does* have internal rollback resistance via `Tag::ROLLBACK_RESISTANCE`, but
that flag only governs whether the *key blob itself* survives a factory reset; it
is **not surfaced to apps as a readable/writable counter**. There is therefore
**no app-usable hardware monotonic counter on any Android tier** in which to hold
the replay floor. The old draft's central mechanism is impossible. Three
secondary defects in that draft must also be fixed:

- **Commit ordering bug.** The old draft advanced the floor *before* the policy
  was durably committed. A crash after advancing but before commit makes the
  same valid bundle a permanent `REGRESSION` — a self-inflicted, unrecoverable
  update-freeze on the *legitimate* policy.
- **Genesis contradiction.** The old draft said both "absent counter ⇒ reject
  all (fail closed)" *and* "genesis: accept the first signed bundle." Those are
  contradictory and must be reconciled honestly.
- **No audience binding.** PROTOCOL.md bundles ([`PROTOCOL.md:100`–`136`](../PROTOCOL.md))
  carry no signed child/device identifier, so a valid bundle for child A replays
  onto child B under the same parent key.
- **Time-anchor rollback.** Freshness rests on `not_before`/`not_after` plus a
  signed time anchor ([`PROTOCOL.md:284`–`295`](../PROTOCOL.md)). If the anchor
  or its cache can be snapshotted backward, an expired-but-never-applied
  high-`policy_seq` bundle can be revived inside a re-opened window.

## Options

1. **Status quo (DataStore floor, post-hoc parent detection).** Rejected:
   violates fail-closed; rollback is live until the next sync; floor is
   poisonable; JC1 unaddressed.
2. **Hash-chain the floor only (rely on `prev_hash` per [`PROTOCOL.md:37`](../PROTOCOL.md)).**
   The chain detects a *forked* writer log ([`PROTOCOL.md:270`](../PROTOCOL.md)
   `CHAIN_BREAK`), but a restored snapshot is a *consistent* older chain, not a
   fork. Does not prevent snapshot rollback. Rejected as sufficient on its own
   (kept as a complementary signal).
3. **Server-side seq oracle.** Rejected outright — violates the no-server
   non-negotiable ([`CLAUDE.md`](../../CLAUDE.md),
   [ADR-006](006-privacy-no-server.md)).
4. **Hardware rollback-resistant monotonic counter as the authoritative floor.**
   **Rejected — the API does not exist.** Android exposes no app-readable/-settable
   hardware monotonic counter (see Context). This was the previous draft's
   decision; it cannot be implemented.
5. **Defense-in-depth: best-effort TEE-bound floor + hash-chain mirror + the
   *parent* as the authoritative monotonicity anchor, fail-closed on every local
   anomaly, short freshness window, audience-bound bundles, single `policy_seq`
   name, rotation-carried floor, JCS integer bound.** Chosen.

## Decision

Adopt **option 5**. The honest posture is: **on-device, a fully-rooted child
cannot be *perfectly* prevented from rolling back its own local state**, because
Android exposes no app-usable hardware monotonic counter (Context) and
[ADR-006](006-privacy-no-server.md) forbids a server to act as the seq oracle.
Replay/rollback resistance is therefore **defense-in-depth that minimizes the
window, detects the anomaly, and fails closed** — with the **parent device as the
authoritative monotonicity anchor** (consistent with no-server: the authority is
the *parent device*, never a cloud). Seven parts, all required.

### 1. Best-effort at-rest floor (TEE/StrongBox-bound) + append-only chain mirror

`last_applied_policy_seq` is the local replay floor. It is persisted **twice**,
neither of which is claimed to be hardware-rollback-proof:

1. **TEE/StrongBox-bound encrypted storage** — the existing encrypted DataStore
   whose master key is StrongBox-backed where available, TEE-backed otherwise
   ([`CRYPTO.md:336`](../CRYPTO.md), [`CRYPTO.md:345`](../CRYPTO.md)). This binds
   the floor's *confidentiality and integrity-at-rest* to hardware-held keys, so
   the value cannot be edited in place without the key. It does **not** make the
   value monotonic across a whole-snapshot restore — encryption protects bytes,
   not version.
2. **Mirrored in the append-only, hash-chained event log** — the floor advance is
   recorded as (or alongside) the `AckPolicy{policy_seq,"applied"}` entry
   ([`PROTOCOL.md:63`–`65`](../PROTOCOL.md), [`PROTOCOL.md:153`–`154`](../PROTOCOL.md))
   in the child's own `prev_hash`-chained log ([`PROTOCOL.md:37`](../PROTOCOL.md),
   [`PROTOCOL.md:43`–`45`](../PROTOCOL.md)). This gives a second, chain-checkable
   witness of the highest `policy_seq` the child has acted on.

On read, the floor is `max(at_rest_floor, highest_policy_seq_in_chain)`. If the
two disagree such that the at-rest value is **lower** than a `policy_seq` the
chain shows already acted on, that is a rollback anomaly → fail closed (part 3).
A whole-device snapshot that rolls *both* back in lockstep is not locally
detectable; it is caught by the parent (part 2).

### 2. The parent is the authoritative monotonicity anchor

Because no on-device anchor is tamper-proof, **monotonicity authority lives on
the parent device**, enforced on **every** sync ([`PROTOCOL.md:204`–`242`](../PROTOCOL.md)
state machine). On each reconcile the parent:

1. Tracks the highest `policy_seq` it has ever issued and the highest the child
   has `AckPolicy`-ed ([`PROTOCOL.md:63`](../PROTOCOL.md)).
2. Verifies the child's reported floor and event-log chain are **monotonically
   non-decreasing** versus the last sync ([`PROTOCOL.md:224`](../PROTOCOL.md)
   `HelloDigest` tip + `prev_hash`).
3. On any **regression**, **forked chain** (`CHAIN_BREAK`,
   [`PROTOCOL.md:270`](../PROTOCOL.md)), or **length/tip anomaly**: raises a
   silence/tamper alarm to the parent ([`ATTACKS.md:86`](../ATTACKS.md) offline /
   silence-alarm tiers) and **pushes a fresh bundle that re-asserts the strict
   baseline** at a `policy_seq` above the highest ever seen. The parent is the
   trust anchor; a rollback the child cannot see, the parent can — and corrects.

This is the no-server-compatible substitute for a seq oracle: the oracle is the
*parent device the family already owns*, not a cloud service.

### 3. Child fails CLOSED to strict baseline on any local anomaly

The child never has an "accept and detect later" branch. Any of the following is
an anomaly that immediately drops the child to the strict baseline
([`PROTOCOL.md:282`](../PROTOCOL.md), "strictest baseline … never times out into
unrestricted"):

- at-rest floor reads **lower** than a `policy_seq` already witnessed in the
  hash-chained log (part 1);
- the event-log chain breaks (`prev_hash` mismatch, [`PROTOCOL.md:270`](../PROTOCOL.md));
- the provisioning marker says "provisioned" but the floor is **missing**
  (part 4 distinguishes this from genesis);
- a **time-anchor rollback** is detected (part 5).

Strict baseline persists until a fresh, valid, in-order, audience-matched bundle
with `policy_seq > floor` is applied. This satisfies the
[`CLAUDE.md`](../../CLAUDE.md) fail-closed invariant.

### 4. Genesis (TOFU) vs anomaly — reconciled with a TEE-bound provisioning marker

The contradiction in the old draft is resolved by distinguishing two states with
a **TEE/StrongBox-bound provisioning marker** written at pairing
([`PROTOCOL.md:415`–`417`](../PROTOCOL.md) pins parent pubkey;
[`CRYPTO.md:268`–`283`](../CRYPTO.md) StrongBox-wrapped pinned store):

- **Never provisioned (genesis / TOFU).** No provisioning marker, no pinned
  parent key, no floor. The child accepts the **first** valid signed bundle whose
  `policy_seq ≥ 1`, pins the parent pubkey, writes the provisioning marker, and
  seeds the floor to that `policy_seq`. `policy_seq = 0` is reserved and never
  trusted as a live policy (preserves [`PROTOCOL.md:36`](../PROTOCOL.md): `seq`
  starts at 0 for the genesis *log entry*).
- **Provisioned, floor now missing or lower.** Provisioning marker present (or a
  pinned parent key present) but the floor is gone / regressed. This is an
  **anomaly** ⇒ fail closed to the strict baseline (part 3). It is **never**
  treated as genesis.

**Honest limitation:** a fully-rooted attacker who destroys *both* the marker and
the floor is, on-device, **indistinguishable from a genuine first provisioning**.
That forces a **re-pair** (genesis TOFU on the next bundle), and the re-pair is
**observable to the parent** — a new pairing / new child identity is exactly what
the parent app surfaces ([`CRYPTO.md:327`–`330`](../CRYPTO.md) re-pair handling,
[`PROTOCOL.md:413`](../PROTOCOL.md) pin state). The parent, as trust anchor,
sees the device dropped to unprovisioned and re-paired. On-device genesis TOFU is
unavoidable without an app-usable hardware anchor; we make it *loud at the parent*
rather than silent.

### 5. Freshness bound to a monotonic clock + parent re-anchor; anchor rollback = anomaly

Freshness (`not_before`/`not_after`, [`PROTOCOL.md:127`–`128`](../PROTOCOL.md),
[`PROTOCOL.md:148`–`149`](../PROTOCOL.md)) must not be revivable by snapshotting
the time anchor or its cache backward. Per [`PROTOCOL.md:284`–`295`](../PROTOCOL.md):

- Window evaluation uses `parent_anchor + (SystemClock.elapsedRealtime() now −
  elapsedRealtime at anchor)`. `elapsedRealtime()` is kernel-monotonic and
  survives wall-clock changes ([`PROTOCOL.md:290`](../PROTOCOL.md)).
- The anchor is re-established only by a **signed parent timestamp**
  (`bundle.issued_at` at apply, or `Heartbeat.issued_at`,
  [`PROTOCOL.md:291`–`292`](../PROTOCOL.md)). A bundle's `not_after` cannot be
  re-opened by editing local time.
- The persisted `(parent_anchor, elapsedRealtime_at_anchor)` pair is itself
  treated as monotonic. If, on read, the stored anchor or last-applied
  `not_after` watermark is **lower** than one already witnessed (i.e. the anchor
  was rolled back), that is an **anomaly** ⇒ strict baseline (part 3), not a
  silent re-acceptance. Defeats F1/F2/F3 ([`ATTACKS.md:53`](../ATTACKS.md),
  [`ATTACKS.md:84`](../ATTACKS.md)) plus the snapshot-revival variant.

### 6. Audience binding: bundles MUST name the device/child they address

PROTOCOL.md bundles ([`PROTOCOL.md:100`–`136`](../PROTOCOL.md)) carry no signed
recipient, so a bundle validly signed for child A replays onto child B under the
same parent key. Fix: every `PolicyBundle` MUST carry a **signed**
`child_device_id` (the child's pinned Ed25519 pubkey, or a stable id derived from
it) inside the signed body. The child rejects (`MALFORMED`, before applying) any
bundle whose `child_device_id` ≠ its own. This is a required schema addition to
PROTOCOL.md §2 (see "Doc changes required").

### 7. Minimize the replay window: short `not_after`, ratchet toward strict baseline

A successful local rollback can, at worst, revive a *recently-valid* policy and
only **briefly**. Default `not_after` is **short** (freshness window measured in
hours, not weeks), so a revived bundle expires quickly into stale-policy /
strict-baseline mode ([`PROTOCOL.md:282`](../PROTOCOL.md)). After **N hours with
no parent contact** the child **ratchets toward the strict baseline** rather than
coasting on the last permissive policy (cross-ref the offline / silence-alarm
ladder, [`ATTACKS.md:86`](../ATTACKS.md); parent alerts at 15 min / 1 h / 6 h /
24 h). Shorter windows trade more frequent sync for a smaller rollback payoff;
this is the right trade for a fail-closed product.

### Carried-forward decisions (unchanged from the prior draft; these were sound)

These parts of the earlier analysis are correct and survive verbatim:

- **One counter name, one scope.** `cmd_seq` is **eliminated**. The single
  monotonic replay counter is `policy_seq` ([`PROTOCOL.md:125`](../PROTOCOL.md)),
  **device-global**, NOT keyed by parent pubkey. The pubkey-keyed
  `lastSeenSeq(parentEd)` ([`CRYPTO.md:250`](../CRYPTO.md)) is replaced by a
  single device-global `policy_seq` floor.
- **Rotation carries the floor forward; rotation never lowers it.** A `RotateKey`
  bundle ([`CRYPTO.md:288`](../CRYPTO.md), [`PROTOCOL.md:417`](../PROTOCOL.md)) is
  a `SignedBundle` carrying its own `policy_seq`, signed by the **old** key
  ([`CRYPTO.md:296`](../CRYPTO.md)). The child (a) verifies it against the
  currently pinned `parent_ed25519_pub` ([`CRYPTO.md:300`](../CRYPTO.md)), (b)
  enforces `policy_seq > floor` and **advances** the floor, (c) atomically swaps
  the pinned pubkeys. Because the floor is device-global, the *new* key inherits
  the *same* floor; old-key bundles (all at `policy_seq ≤ floor`) are dead.
  Recovery-derived keys are bit-identical to the originals
  ([`CRYPTO.md:302`](../CRYPTO.md), [`CRYPTO.md:319`](../CRYPTO.md)), so
  phrase-based recovery needs no special case — same pubkey, same floor.
- **JCS integer bound 0 .. 2^53−1.** All JCS-serialized integer/timestamp fields
  — `policy_seq`, `seq`, `issued_at`, `not_before`, `not_after`
  ([`PROTOCOL.md:36`](../PROTOCOL.md), [`:38`](../PROTOCOL.md),
  [`:125`–`:128`](../PROTOCOL.md)) — are bounded to the **JCS-safe integer range,
  0 .. 2^53−1 (9007199254740991)**. The type label changes from "u64" to
  "u53-bounded integer." A bundle with any such field > 2^53−1 is rejected
  `MALFORMED` *before* signature verification (alongside the
  [`PROTOCOL.md:170`](../PROTOCOL.md) "integers only / no floats" rule), so signer
  and verifier always produce byte-identical canonical bytes (RFC 8785 §3.2.2.3
  round-trips exactly within this range). 2^53−1 ms is year ~287396 and 2^53
  policy updates is unreachable, so the bound costs nothing operationally.
- **`MAX_SEQ_JUMP = 1024` against floor-poison DoS.** The child rejects
  `MALFORMED` any bundle with `policy_seq > floor + MAX_SEQ_JUMP`. A single
  malicious or buggy bundle can no longer ratchet the floor to an unreachable
  value and permanently freeze updates; worst case is a bounded, recoverable skip.

### Commit ordering (two-phase, crash-safe, idempotent) — fixes the old draft

The floor advances **only after** the policy is durably applied. No floor advance
ever precedes a durable commit. Replacing
[`PROTOCOL.md:152`–`154`](../PROTOCOL.md):

```
verify_bundle(bundle, pinned_parent_ed25519_pub, my_child_device_id,
              floor := max(at_rest_floor, highest_policy_seq_in_chain),
              monotonic_now_ms):
  1.  if bundle.v != 1:                              reject MALFORMED
  2.  if size(canonicalize(bundle)) > 65536:         reject MALFORMED
  3.  if any int/ts field > 2^53-1:                  reject MALFORMED   // JC1
  4.  if bundle.child_device_id != my_child_device_id: reject MALFORMED // audience (part 6)
  5.  body := canonicalize(bundle without "sig")
  6.  if not Ed25519.verify(bundle.sig, body, pinned_parent_ed25519_pub):
                                                      reject SIG_FAIL (fail-closed)
  7.  if bundle.policy_seq <= floor:                 reject REGRESSION
  8.  if bundle.policy_seq > floor + MAX_SEQ_JUMP:   reject MALFORMED   // floor-poison DoS
  9.  if monotonic_now_ms <  bundle.not_before:      reject CLOCK_SKEW  (defer)
  10. if monotonic_now_ms >= bundle.not_after:       reject EXPIRED     (stale-policy mode)
  11. if anchor_or_not_after_watermark rolled back:  enter STRICT BASELINE (anomaly, part 5)
  // ---- two-phase durable apply; floor advances LAST ----
  12. STAGE the new policy to a temp record                    // not yet live
  13. apply(bundle.policy); fsync the applied policy + staged record  // durable
  14. ADVANCE floor := bundle.policy_seq                       // at-rest + chain mirror, AFTER 13
  15. emit AckPolicy{policy_seq, "applied"}                    // chain witness (part 1)
```

Crash safety: a crash between 12 and 14 leaves the **old** floor in force, so the
**same** valid bundle re-applies cleanly on restart (`policy_seq == floor` is not
`<` floor; re-application is idempotent — applying the identical staged bundle is
a no-op that simply re-runs 13→15). A crash never strands a legitimate bundle as
a permanent `REGRESSION`, which was the old draft's bug. Steps 6 and 9–11 remain
fail-closed ([`PROTOCOL.md:157`](../PROTOCOL.md)).

## Consequences

**Good:**

- The replay window is **minimized and detected**, and every anomaly **fails
  closed** to the strict baseline — satisfying the [`CLAUDE.md`](../../CLAUDE.md)
  fail-closed invariant *without* depending on a nonexistent API.
- The parent is a real, no-server monotonicity authority: a rollback the child
  can't see, the parent catches on the next sync and corrects by pushing a
  higher-`policy_seq` strict baseline.
- Audience binding kills cross-child bundle replay under one parent key.
- One counter name (`policy_seq`), one scope (device-global), eliminates the
  `cmd_seq` vs `policy_seq` ambiguity and the replay-after-rotate hole.
- JCS canonical bytes round-trip exactly: no signer/verifier disagreement, no
  legitimate bundle silently failing `SIG_FAIL`, no permanent update-freeze DoS.
- Commit ordering is crash-safe and idempotent; a crash can never strand the
  legitimate policy as a permanent regression.
- Genesis vs anomaly is unambiguous on-device, and the unavoidable TOFU residue
  is made loud at the parent rather than silent.

**Bad — honest limits the maintainer must accept:**

- **This is window-minimization + detection + fail-closed, NOT absolute
  on-device prevention.** A fully-rooted child that snapshots *all* local state
  (floor + chain + anchor + provisioning marker) in lockstep and restores it
  while offline **can** revive a recently-valid permissive bundle until its short
  `not_after` expires or the next parent sync. Absolute prevention would require
  either a trusted hardware monotonic counter Android does **not** expose to apps
  (Context) or a server seq oracle, which [ADR-006](006-privacy-no-server.md)
  rules out. We do not claim more than we can deliver.
- **There is no app-usable rollback-resistant counter on ANY Android tier** —
  not Tier 1 (Pixel/Titan M2), not Tier 2 (Knox Vault, etc.), not Tier 3
  ([`ANDROID_COMPAT.md:89`–`130`](../ANDROID_COMPAT.md)). StrongBox vs TEE affects
  only *at-rest key isolation* for the floor's confidentiality/integrity, **not**
  monotonicity. The parent app SHOULD still surface attestation tier in pairing
  ([`ANDROID_COMPAT.md:151`](../ANDROID_COMPAT.md)), but MUST NOT advertise
  "hardware rollback resistance" on any tier.
- **Genesis TOFU is unavoidable on-device:** an attacker who erases both marker
  and floor is indistinguishable from a first pairing and forces a re-pair. The
  mitigation is parent observability, not on-device prevention.
- Short default `not_after` (part 7) means children must sync more often or drop
  to strict baseline sooner — a deliberate availability-for-safety trade.
- The `cmd_seq` removal, the `u64 → u53-bounded` retype, the `child_device_id`
  addition, and the commit-ordering rewrite are wire/verify-algorithm doc changes.
  The `child_device_id` addition is a **new mandatory signed field** in the bundle
  body; per [`PROTOCOL.md:425`](../PROTOCOL.md) a new mandatory field is a
  format-break candidate — but because v1 has **no shipped bundles yet** (Phase 0),
  this is folded into v1 with **no `v` bump**. Test vectors must be regenerated.

## Doc changes required

- **[`CRYPTO.md:339`](../CRYPTO.md), [`:341`](../CRYPTO.md) (§8 list):** restate
  "Last-seen `cmd_seq` (child)" and "`policy_seq` … watermarks (child)" as a
  single `policy_seq` floor that lives in **TEE/StrongBox-bound encrypted
  DataStore (best-effort at-rest integrity only) AND is mirrored in the
  append-only hash-chained event log**; explicitly note this is *not* a hardware
  monotonic counter.
- **[`CRYPTO.md:352`](../CRYPTO.md) (§8):** replace "Counters (kid can read;
  rolling them back is detected by parent's monotonicity check on next sync)"
  with the honest posture: the at-rest floor is not rollback-proof; a
  lower/absent reading vs the chain witness fails closed to strict baseline at
  apply time, and the **parent is the authoritative monotonicity anchor** that
  detects and corrects whole-snapshot rollback on the next sync.
- **[`CRYPTO.md:224`–`262`](../CRYPTO.md) (§5):** rename `cmd_seq` → `policy_seq`
  throughout (`SignedBundle.cmd_seq` line 233, `bundle.cmd_seq <= lastSeenSeq(parentEd)`
  line 250, `persistSeq` line 257, `(parent_ed25519_pub, cmd_seq)` prose line 262).
  Replace pubkey-keyed `lastSeenSeq(parentEd)` with the device-global floor; add
  the audience check (`child_device_id`) and the two-phase commit ordering (floor
  advances only after durable apply); state that rotation carries the floor
  forward and never lowers it.
- **[`CRYPTO.md:23`–`24`](../CRYPTO.md) (§1 key inventory):** collapse the two
  rows "`policy_seq` counter" and "`cmd_seq` counter" into a single
  "`policy_seq` floor" row; child storage = "TEE/StrongBox-bound encrypted
  DataStore + event-log chain mirror (best-effort; **not** a hardware monotonic
  counter)."
- **[`CRYPTO.md:288`–`304`](../CRYPTO.md) (§6 Rotation):** add the explicit rule
  that `RotateKey` advances the same device-global `policy_seq` floor and cannot
  reset it.
- **[`PROTOCOL.md:100`–`136`](../PROTOCOL.md) (§2 PolicyBundle schema):** add a
  **new mandatory signed field** `child_device_id` (the addressed child's pinned
  Ed25519 pubkey / stable id); the child rejects `MALFORMED` any bundle not
  addressed to its own id (audience binding).
- **[`PROTOCOL.md:170`](../PROTOCOL.md) (§3.1 rule 4):** add the integer bound —
  all JCS-serialized integers/timestamps MUST be in `0 .. 2^53−1`; values outside
  are rejected `MALFORMED` before signature verification (reconciles with §1.1).
- **[`PROTOCOL.md:36`](../PROTOCOL.md), [`:38`](../PROTOCOL.md),
  [`:125`–`128`](../PROTOCOL.md) (§1.1 + §2 field tables):** retype `seq`,
  `policy_seq`, `issued_at`, `not_before`, `not_after` from `u64` to
  "u53-bounded integer (0 .. 2^53−1)."
- **[`PROTOCOL.md:140`–`155`](../PROTOCOL.md) (§2.1 verify algorithm):** replace
  with the Decision's two-phase, crash-safe ordering — read floor as
  `max(at_rest, chain)`; check audience and JCS bound before signature; check
  `MAX_SEQ_JUMP = 1024`; **stage → apply+fsync → advance floor → AckPolicy**, in
  that order; floor advance never precedes durable commit.
- **[`PROTOCOL.md:276`–`282`](../PROTOCOL.md) (§5 replay+freshness):** state the
  floor is best-effort at-rest + chain-mirrored, the **parent** is the
  authoritative monotonicity anchor (regression/fork/length anomaly ⇒ parent
  alarm + strict-baseline push), the child fails closed to strict baseline on any
  local anomaly, default `not_after` is short, and the child ratchets to strict
  baseline after N hours without parent contact. Genesis: a never-provisioned
  child (no marker, no pinned key, no floor) TOFU-accepts the first valid bundle
  with `policy_seq ≥ 1`; a *provisioned* child with a missing/lower floor is an
  anomaly ⇒ strict baseline; `policy_seq = 0` is never a live policy.
- **[`PROTOCOL.md:284`–`295`](../PROTOCOL.md) (§5.1 clock sources):** state that
  freshness is bound to `SystemClock.elapsedRealtime()` + signed parent re-anchor
  (`bundle.issued_at` / `Heartbeat.issued_at`), and that a rolled-back time
  anchor or `not_after` watermark is an anomaly ⇒ strict baseline (defeats the
  snapshot-revival variant of F1/F2).
- **Test vectors ([`PROTOCOL.md:435`–`447`](../PROTOCOL.md),
  [`CRYPTO.md`](../CRYPTO.md) §10):** add `bundle-05-reject-seq-overflow`
  (`policy_seq > 2^53−1` ⇒ `MALFORMED`), `bundle-06-reject-seq-jump`
  (`policy_seq > floor + 1024` ⇒ `MALFORMED`), `bundle-07-reject-wrong-audience`
  (`child_device_id` mismatch ⇒ `MALFORMED`), a snapshot-rollback negative case
  (at-rest floor < chain witness ⇒ strict baseline), a crash-during-apply
  idempotency case (re-apply same bundle after crash ⇒ no `REGRESSION`), and a
  time-anchor-rollback case (rolled-back anchor ⇒ strict baseline).
- **[`ANDROID_COMPAT.md`](../ANDROID_COMPAT.md) §3:** add a note that **no
  app-usable rollback-resistant monotonic counter exists on ANY tier** (Tier 1
  Pixel included); StrongBox vs TEE affects only at-rest key isolation, not
  monotonicity; the parent app MUST NOT advertise "hardware rollback resistance."

## Cross-refs

- [`docs/CRYPTO.md`](../CRYPTO.md) (§1, §5, §6, §8)
- [`docs/PROTOCOL.md`](../PROTOCOL.md) (§1.1, §2, §2.1, §3.1, §5)
- [`docs/ANDROID_COMPAT.md`](../ANDROID_COMPAT.md) (§3 StrongBox/TEE — no monotonic counter on any tier)
- [`docs/ATTACKS.md`](../ATTACKS.md) (H1/C8 replay, F1/F2/F3 clock rollback, G2/H2 fail-closed, J1/N1 silence/offline alarms)
- [`docs/DEFENSES.md`](../DEFENSES.md) (fail-closed, StrongBox/TEE-bound at-rest)
- [`CLAUDE.md`](../../CLAUDE.md) (invariants: replay protection mandatory, fail-closed, recovery phrase = root authority)
- [ADR-006](006-privacy-no-server.md) (no-server constraint rules out a seq oracle → parent device is the authority)
- [Android `KeyGenParameterSpec.Builder`](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder) (no `setRollbackResistant`; only `setIsStrongBoxBacked` / `setMaxUsageCount`)
