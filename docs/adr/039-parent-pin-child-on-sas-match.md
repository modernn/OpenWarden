# ADR-039: Pin the child pubkeys on SAS Match — atomic, write-once, recovery-gated rotation (parent slice e)

Status: Accepted
Date: 2026-06-23
Implements: **docs/PROTOCOL.md §7.4** (its closing clause — *"Match → child's pubkeys enter `pinned` state in parent app"* — is the parent-side pin) + **ADR-025 D5(e)** (the "pin `(child_ed25519_pub, child_x25519_pub)` only on Match" slice) and **D8** (the parent-side recovery-gated-rotation gate this preserves). NB: PROTOCOL **§7.5**'s own text is the *child* writing the *parent* keys; the parent-side rotation rule is canon in **ADR-025 D8 + DEFENSES #15**, not §7.5.
Discharges: the **ADR-038 D4a** disclosed residual — slice (d) shipped the SAS stage with "no production caller drives `confirm()` yet"; this slice adds the coordinator that does
Relates: ADR-025 (pairing handshake ratified; the §7.5 pin is the trust anchor that closes H3), ADR-035/036/037/038 (parent slices a/b/c/d — session+QR / endpoint / attestation-verify / SAS), ADR-034 D4 (the existing `AndroidPairedChildStore` / `PairedChildStore` audience-binding contract this slice extends), ADR-015 (X25519 sealed-box audience = the child key pinned here); docs/ATTACKS.md H3 (pubkey substitution, CRITICAL); docs/DEFENSES.md #4 (identity pinning), #15 (recovery-gated rotation); issue #98 (re-scope of #23)
Maintainer-approved: attended agent-blocked review, 2026-06-23 (scope = pin core + coordinator that discharges D4a; Compose UI + Android transport wiring deferred — see D5a).

## Context

PROTOCOL §7.4 (ratified by ADR-025) ends with: *"Parent taps 'Match' → child's pubkeys
enter `pinned` state in parent app."* That pin is the **trust anchor** (§7.5 / ADR-025 D8) — the pinned
`(child_ed25519_pub, child_x25519_pub)` is the audience for every subsequent sealed-box event
(ADR-015) and the identity the signed-bundle sender addresses (ADR-034 D4). Getting the pin
wrong is the **H3 pubkey-substitution** attack (ATTACKS.md, CRITICAL): a parent that pins the
wrong key hands the attacker the event-log audience and the command channel.

Slices (a)–(d) built everything *up to* the pin and stopped there, by design:

- (d) `PairingSasStage.confirm(matched = true)` returns `SasOutcome.Match(childEd, childX)` and
  **burns nothing** — its KDoc says it "leaves session live for slice (e) to pin + consume";
- `PairingSessionManager.consume()` is "take + burn the live session for the success handoff
  (driven by slice (e))" — unused until now;
- `AndroidPairedChildStore` persists **only** `child_device_id` (the Ed-derived id, ADR-034 D4);
  it has no slot for `child_x25519_pub`, the sealed-box audience §7.5 requires it to pin;
- **ADR-038 D4a** disclosed the residual that "no production caller drives `confirm()` yet" — the
  coordinator joining endpoint-`Accepted` → derive → human-compare → `confirm()` does not exist.

This slice writes the pin and the coordinator that drives it, fail-closed, and leaves rotation
exactly as §7.5/D8 mandates (recovery-phrase + 24 h) — un-weakened, because weakening it is the
other half of the H3 attack.

## Decision

**D1 — Pin = persist BOTH child public keys, only on Match.** On `SasOutcome.Match` the parent
commits **both** `child_ed25519_pub` (the identity / `child_device_id`) **and**
`child_x25519_pub` (the sealed-box audience, ADR-015) to the pinned-child store. The existing
ADR-034 store held only the Ed key; this slice extends it with the X25519 key. The `Mismatch`,
`Stale`, attestation-`Refused`, and every error path pin **nothing** (fail-closed) — a pin only
ever happens after attestation (c) returned `Accepted` **and** the human tapped Match (d).

**D2 — Atomic: both keys or neither, never half-pinned.** The two keys are written in a **single**
`EncryptedSharedPreferences` `commit()` (synchronous, all-or-nothing), so a crash or write failure
cannot leave one key pinned without the other. A read returns a pinned child **only when both keys
are present and decode to exactly 32 bytes** (D6 byte validation); a lone key reads as **unpaired**.
A `commit()` that returns `false` throws (fail-closed) — nothing is pinned and the session is **not**
consumed, so the attempt resolves to unpaired, never a stuck half-trusted state (ADR-025 "Blocking"
abort item).

**D3 — Write-once; double-pin / re-pair is refused; rotation is recovery-gated (§7.5/D8).** The pin
is **write-once**. A second pin while a child is already pinned is **refused fail-closed** at two
layers: the coordinator returns `AlreadyPaired` without calling the store, and the store's `pin()`
itself `check`s the slot is empty and throws otherwise (hard floor — even if a caller bypasses the
coordinator). There is **no un-gated overwrite path.** Replacing a pinned child (rotation / re-pair)
**requires the parent's BIP39 recovery phrase + a 24-h delay** (§7.5, Defense #15) — that mechanism
is **deferred to its own issue and is NOT implemented or weakened here.** Until it lands, the only
way to re-pair is the recovery flow; a fresh QR cannot silently steal an already-pinned slot. This
is the second half of the H3 defense (pin + recovery-gated rotation). The coordinator burns the
already-paired attempt's session on this path (single-use: it reached a human Match), returning
`AlreadyPaired`. **The future pairing UI (D5a) MUST surface `AlreadyPaired` distinctly from a SAS
`Aborted`** — a paired parent must be told to use recovery-gated rotation, never left at a silent dead
session (a behavior the maintainer signs off at merge).

**D4 — Burn the single-use session only after a durable pin.** On a successful pin the coordinator
calls `consume()` to burn the `provisioning_nonce` (single-use, §7.1) — **after** the store write
returns, so a failed write never burns a still-usable attempt. `Mismatch` already burned the nonce
inside `confirm()` (ADR-036 D4 HARD criterion, inherited); the coordinator does not double-burn.
`Stale` burns nothing (the challenge's attempt is already dead; the current live attempt must not be
touched — ADR-038's session-bound-confirm rule).

**D5 — Discharge ADR-038 D4a: a pure `PairingPinCoordinator` drives `confirm()` → pin → consume.**
The coordinator is the production caller D4a said was missing: `confirmAndPin(challenge, matched)`
calls `PairingSasStage.confirm`, and on `Match` performs the D1–D4 pin + burn, returning a
`PinOutcome` (`Pinned` / `Aborted` / `Stale` / `AlreadyPaired`). It is **pure `commonMain`** over
three seams — `PairingSasStage`, a `PinnedChildStore` (read/write-once), and a
`PairingSessionConsumer` (`consume()`) — so the whole Match → pin → burn lifecycle, the write-once
refusal, and the half-pin impossibility are **host-deterministic** in `commonTest`.

**D5a — Disclosed residual: still no Compose UI and no Android transport wiring.** Exactly as (d)
disclosed D4a, this slice ships the coordinator + the pin **without** (i) the Compose screen that
renders the six emojis and captures the Match/Mismatch tap, and (ii) the `PairingServer` change that
drives `endpoint → derive → coordinator.confirmAndPin` under the shared `sessionLock` (ADR-036 D5).
The coordinator exposes the seam the UI and transport will call; wiring them is the next
(agent-blocked) issue. What **is** proven here, deterministically: the coordinator wired to a real
`SessionAccess`/consumer + a `PinnedChildStore` pins both keys on Match and burns the session so the
endpoint's next POST is `NO_SESSION`, refuses a second pin, and pins nothing on any failure branch.
The boundary is intentional and tracked, not a silent gap.

**D6 — Byte-level key validation on read (ADR-025 D6, restated).** Wherever the store reads a pinned
key back it base64url-**decodes** and asserts exactly 32 bytes; a malformed or wrong-length stored
value reads as **unpaired** (fail-closed), never a truncated/over-long blob handed to a sealed-box
consumer. The keys carried into `pin()` are already the endpoint-decoded exactly-32-byte arrays
(slice (b) D6), so a bad key can never reach the pin in the first place.

**D7 — Fail-closed everywhere; the non-negotiables win.** No pin on any error. The pinned-child
store stays in the parent's StrongBox-backed `EncryptedSharedPreferences` (tamper-resistance; the id
is not secret but a flipped audience is a fail-closed child-side rejection, not a silent mis-address).
No new wire field, no `proto` change — the pin is parent-local state.

## Consequences

Good:
- **The §7.4 parent-side pin is now buildable end-to-end:** Match writes the trust anchor; the
  one-ADR-per-slice rhythm (035/036/037/038) closes with (e).
- **H3 is defended both halves:** the pin commits the SAS-confirmed keys, and rotation stays
  recovery-gated (D3) — neither a MITM at pair time nor a later silent re-pair can swap the audience.
- **The X25519 audience is finally persisted**, so sealed-box event decryption (ADR-015) has a real
  pinned recipient instead of the Ed-only id.
- **ADR-038 D4a is discharged**: `confirm()` now has a production caller, host-proven.
- **Atomicity + write-once are host-deterministic**, not reliant on a skippable device round-trip.

Bad / accepted limits:
- **No UI / no transport wiring yet (D5a).** The coordinator is not yet reachable from a running app;
  a human cannot pair through a screen until the next issue. Tracked, not silent.
- **Rotation is deferred (D3).** A parent who must re-pair (lost/replaced child device) has no path
  until the recovery-gated rotation issue lands. Accepted: an un-gated overwrite would reopen H3, so
  "no rotation yet" is strictly safer than "easy rotation."
- **The Android store's real `EncryptedSharedPreferences` round-trip is not host-tested.** The module
  ships no Robolectric (androidUnitTest is pure-crypto only) and the MasterKey needs the Android
  KeyStore, so the real adapter's read/write is covered on-device / by an instrumented test (deferred),
  while the **contract** (atomic write-once, no-half-pin, pin-then-burn, refuse-on-failure) is proven
  deterministically in `commonTest` — against the `PinnedChildStore` seam (the coordinator tests) **and**
  against a faithful string-backed store model (`PinnedChildStoreContractTest`: pin → read-back both
  keys, second-pin throws + first stands, lone/malformed stored key reads unpaired). Per docs/TESTING.md
  the fail-closed logic lives in the host tier; the adapter is a thin platform shim.
  **Tracked HARD pre-prod gate (this trust anchor must not ship to production on host tests alone, same
  pattern as #96's deferred X.509 path-validation):** an instrumented/Robolectric test of the *real*
  `AndroidPairedChildStore` asserting (i) both keys present after a successful pin, (ii) a simulated
  `commit() == false` leaves `pinnedChild() == null`, (iii) a second `pin()` throws and the first pin
  stands, (iv) a malformed/lone stored key reads unpaired — filed against the slice-e follow-up.
- **No iOS pinned-child store.** Android-first parent phase; the iOS `actual` lands with iOS parent work.

## Test plan (binds the implementation)

- **Pin on Match:** `confirmAndPin(matched = true)` over a live session ⇒ store holds **both**
  `child_ed25519_pub` and `child_x25519_pub` (byte-equal to the SAS-confirmed keys) and the session is
  consumed (next endpoint POST = `NO_SESSION`). Integration twin of `PairingSasBurnIntegrationTest`.
- **No pin on every failure branch:** `Mismatch` ⇒ nothing pinned, `Aborted`, nonce already burned;
  `Stale` ⇒ nothing pinned, `Stale`, the *other* live attempt untouched; attestation `Refused`
  upstream ⇒ `confirm()` never reached, nothing pinned.
- **Half-pin impossibility:** a store `pin()` that fails (throws) ⇒ the coordinator pins nothing and
  does **not** consume — the attempt stays unpaired; a store with only one key present reads as unpaired.
- **Write-once / double-pin / replay rejected:** with a child already pinned, a second `Match`
  ⇒ `AlreadyPaired`, the original pin **unchanged** (no overwrite), the spent session burned; the
  store's own `pin()` throws on a second write (hard-floor backstop).
- **Byte validation on read (D6):** a malformed/short stored key ⇒ `pinnedChild()` returns null
  (unpaired), never a bad-length array. Proven host-side against the string-backed store model
  (`PinnedChildStoreContractTest`); the real-adapter case is the instrumented HARD gate above.
- **Store-level write-once hard floor:** a direct second `pin()` on a store that already holds a child
  throws (the backstop independent of the coordinator's soft check), and the first pin is unchanged
  (`PinnedChildStoreContractTest`).

## Cross-refs
- [docs/PROTOCOL.md](../PROTOCOL.md) §7.4 (closing clause = the parent-side Match → pin), §7.5 (child-side pin of parent keys; the rotation *principle*), ADR-025 D8 (the parent-side recovery-gated rotation gate)
- [ADR-025](025-pairing-handshake-direction-attestation-sas.md) D5(e)/D8/D6, [ADR-038](038-six-emoji-sas-encoding.md) D4/D4a (the residual discharged here), [ADR-037](037-parent-attestation-verifier-slice-c.md) (the `Accepted` gate before the SAS), [ADR-036](036-parent-pairing-endpoint-pre-auth.md) D4/D5 (burn-on-fail HARD criterion + the shared `sessionLock`), [ADR-034](034-parent-signed-bundle-send.md) D4 (the `PairedChildStore` audience contract extended here), [ADR-015](015-event-log-crypto-primitives.md) (X25519 sealed-box audience)
- docs/ATTACKS.md H3; docs/DEFENSES.md #4 / #15; issue #98 (re-scope of #23)
