# ADR-015: Event-log crypto primitives — sealed-box only, one signing rule

Status: Accepted
Date: 2026-06-16
Accepted: 2026-06-26 — implemented by [ADR-044](044-sealed-box-event-encryption-impl.md) / issue #3.
The mandated PROTOCOL §6.2 nonce fix (BLAKE3 → BLAKE2b, doc-change #5 below) is now applied, and
the `crypto_box_seal` encryption primitive (ruling 1) is KAT-tested against reference libsodium.
Doc-changes #1–#4 (STORE_AND_FORWARD non-normative banners + signing-input unification) remain a
tracked follow-up doc PR and do not affect the implemented crypto.

## Context

A red-team review of the event-log cryptography found three CRITICAL contradictions between [`docs/CRYPTO.md`](../CRYPTO.md), [`docs/PROTOCOL.md`](../PROTOCOL.md), and [`docs/STORE_AND_FORWARD.md`](../STORE_AND_FORWARD.md). Each is a fail-open footgun: a literal implementation of one doc silently fails to interoperate with — or actively breaks the security model of — a literal implementation of another. The core architecture invariant at stake is the one in [`CLAUDE.md`](../../CLAUDE.md): *"Event log encrypted to parent pubkey via libsodium sealed-box"* and *"No content in event log."* The "child cannot read own log" property is the load-bearing wall here.

### SB1 — Authenticated box vs. anonymous sealed box (breaks "child cannot read own log")

[`STORE_AND_FORWARD.md:72`](../STORE_AND_FORWARD.md) specifies the event channel as:

> Each log entry's `payload` is encrypted to the recipient via `box(payload, recipient_pubkey, sender_privkey)` (NaCl/libsodium box semantics).

and adds a forward-secrecy ratchet at [`STORE_AND_FORWARD.md:76`](../STORE_AND_FORWARD.md):

> **Forward secrecy**: Olm-style double-ratchet on the payload channel.

This directly contradicts the mandated **anonymous** sealed box in [`CRYPTO.md:165-167`](../CRYPTO.md) (§4) — *"`libsodium.crypto_box_seal` ... the sender is anonymous (no signature, ephemeral sender key destroyed)"* — and [`PROTOCOL.md:299-301`](../PROTOCOL.md) (§6) — *"Child events are encrypted to the parent's X25519 pubkey using libsodium `crypto_box_seal`. The child writes ciphertext it cannot decrypt."*

The security consequence is fatal. `crypto_box` (authenticated box) derives a shared secret `X25519(sender_priv, recipient_pub)` that **the sender also possesses**. A rooted child holds `sender_priv` (its own X25519 key, even StrongBox-resident, is usable at unlock time). It can therefore recompute the shared secret and **decrypt its own event log** — directly violating the property `CRYPTO.md:200` calls out as the win: *"the child cannot decrypt its own writes."* `crypto_box_seal`, by contrast, uses a per-message **ephemeral** sender key that libsodium zeroizes immediately ([`CRYPTO.md:196-200`](../CRYPTO.md), §4 "Ephemeral key handling"), so no party but the parent can ever decrypt. The double-ratchet is likewise incompatible: a ratchet requires retained per-peer sending-chain state on the child, reintroducing exactly the recoverable sender secret that sealed-box exists to eliminate.

### Nonce hash — BLAKE3 vs. BLAKE2b (silent decrypt failure = fail-open logging)

[`PROTOCOL.md:319`](../PROTOCOL.md) (§6.2) derives the sealed-box nonce with BLAKE3:

> `nonce  := BLAKE3-256(ephemeral_pub || parent_x25519_pub)[0..24]`

and claims at [`PROTOCOL.md:326`](../PROTOCOL.md): *"This is the libsodium sealed-box construction verbatim."* It is **not**. libsodium's `crypto_box_seal` derives the nonce with **BLAKE2b**, matching [`CRYPTO.md:198`](../CRYPTO.md) (§4): *"derives a symmetric key with BLAKE2b."* (Precisely, libsodium computes `nonce = BLAKE2b-192(ephemeral_pk ‖ recipient_pk)`, 24-byte output, no truncation step.) A child that follows PROTOCOL literally produces a 24-byte BLAKE3 prefix; the parent calling `crypto_box_seal_open` recomputes BLAKE2b and gets a different nonce, so the Poly1305 tag check fails and every event silently fails to decrypt. Because event logging is a side channel the parent only notices by its absence, this is a **fail-open** monitoring failure, not a loud crash.

### SG1 — Two different signing-input byte ranges (unsigned malleable fields)

[`PROTOCOL.md:41`](../PROTOCOL.md) (§1.1) and [`CRYPTO.md:237`](../CRYPTO.md) (§5) sign the **whole canonical entry minus the `sig` field**:

- `PROTOCOL.md:41`: *"`Ed25519_sign(canonical_bytes_without_sig_field, owner_privkey)`"* — reinforced at [`PROTOCOL.md:163`](../PROTOCOL.md) (§3, "All Ed25519 signing ... operate on JCS canonical bytes") and [`PROTOCOL.md:342`](../PROTOCOL.md) (§6.3, "The Ed25519 signature is over the canonical envelope minus `sig`").
- `CRYPTO.md:237`: signs `canonical_json({v, cmd_seq, not_before, not_after, payload})`; verification at `CRYPTO.md:247` uses `bundle.withoutSig()`.

[`STORE_AND_FORWARD.md:28`](../STORE_AND_FORWARD.md) instead signs only three fields concatenated:

> `sig: Ed25519(payload || prev_hash || seq, owner_privkey)`

This leaves `v`, `issued_at`, and `payload_type` (PROTOCOL §1.1 entry fields) **outside the signature** and therefore malleable: an attacker who can touch the log can downgrade `v`, shift `issued_at` (the parent-anchored clock source per [`PROTOCOL.md:292`](../PROTOCOL.md) §5.1), or rewrite `payload_type` from `SealedEvent` to the parent-readable `Event` ([`PROTOCOL.md:52-55`](../PROTOCOL.md)) without invalidating the signature. The two rules also disagree on serialization: STORE_AND_FORWARD uses raw `||` concatenation, while PROTOCOL/CRYPTO mandate RFC 8785 JCS canonical bytes ([`PROTOCOL.md:161-163`](../PROTOCOL.md) §3). Concatenation with no length framing is independently ambiguous (`payload‖prev_hash` boundary is not self-delimiting).

## Options

1. **Make STORE_AND_FORWARD normative.** Adopt authenticated `box` + double-ratchet + 3-field signing. Rejected: directly breaks "child cannot read own log" (SB1), adds retained ratchet state, and ships malleable unsigned fields (SG1). This is the regression the red-team flagged.

2. **Hybrid — sealed box for confidentiality, authenticated box for a separate "sender hint."** Rejected: any authenticated-box step reintroduces a child-recoverable shared secret; partial use is as fatal as full use. Adds complexity for no gain — entry-level Ed25519 already supplies authenticity.

3. **Declare CRYPTO.md + PROTOCOL.md normative for all event-log crypto; demote STORE_AND_FORWARD.md to non-normative (transport/semantics only) and correct its crypto prose to defer.** Also fix the one residual error inside PROTOCOL itself (the BLAKE3 nonce). Adopted below. It preserves the invariant, requires no new primitives, and reduces three rules to one.

## Decision

**[`CRYPTO.md`](../CRYPTO.md) and [`PROTOCOL.md`](../PROTOCOL.md) are NORMATIVE for all event-log cryptography. [`STORE_AND_FORWARD.md`](../STORE_AND_FORWARD.md) is non-normative for cryptography and MUST defer to them.** Where STORE_AND_FORWARD describes crypto, it is illustrative only; on any conflict, CRYPTO.md/PROTOCOL.md win. STORE_AND_FORWARD remains authoritative for transport tiers, sync semantics, and reliability/retry behavior.

Three precise rulings:

1. **Event-log encryption primitive: anonymous `crypto_box_seal` (sealed box) ONLY.** No authenticated `crypto_box`, no sender private key in the event path, no double-ratchet on the event channel. The sender keypair used per event is the **ephemeral** keypair generated and zeroized internally by `crypto_box_seal` ([`CRYPTO.md:196-200`](../CRYPTO.md)); the child's long-term X25519 key plays **no role** in encrypting events. This is what preserves "child cannot read own log" ([`CRYPTO.md:200`](../CRYPTO.md), [`PROTOCOL.md:301`](../PROTOCOL.md)). The forward-secrecy ratchet of `STORE_AND_FORWARD.md:76` is OUT OF SCOPE for the event channel and is struck. Sealed box does **not** provide forward secrecy against parent-key compromise — anyone holding the parent X25519 private key can decrypt all captured past logs — but it does guarantee the *child* retains no decryption-capable secret, so compromising the child reveals no event plaintext. Forward secrecy for the event channel is an explicit non-goal (see Consequences).

2. **Sealed-box nonce derivation is exactly libsodium's construction:**

   ```
   nonce = BLAKE2b-192( ephemeral_pk ‖ recipient_pk )     // 24 bytes, no truncation
   ```

   where `ephemeral_pk` is the per-message ephemeral X25519 public key prepended to the ciphertext and `recipient_pk` is `parent_x25519_pub`. **BLAKE3 is struck** from the nonce derivation. Implementations MUST use libsodium `crypto_box_seal` / `crypto_box_seal_open` (or a byte-for-byte equivalent) and MUST NOT hand-roll the nonce. This satisfies the conformance requirement *"Sealed box matches libsodium `crypto_box_seal` byte-for-byte"* ([`PROTOCOL.md:467`](../PROTOCOL.md), §10.1). (BLAKE3-256 remains correct and unchanged for the `prev_hash` log chain at [`PROTOCOL.md:43-45`](../PROTOCOL.md) §1.2 — this ruling does **not** touch the hash chain.)

3. **Signature input is ONE rule, everywhere:** the RFC 8785 (JCS) canonical serialization of the entry **with the `sig` field removed**. For an event/envelope this covers exactly `{v, seq, prev_hash, issued_at, payload_type, payload}` ([`PROTOCOL.md:19-41`](../PROTOCOL.md), §1.1); for a bundle, the equivalent field set minus `sig` ([`CRYPTO.md:237`](../CRYPTO.md), [`PROTOCOL.md:136`](../PROTOCOL.md)). The `payload` of a `SealedEvent` is `{ "ciphertext": "..." }` ([`PROTOCOL.md:336-337`](../PROTOCOL.md)), so the sealed ciphertext is signed transitively as part of the canonical entry. The `STORE_AND_FORWARD.md:28` rule `payload‖prev_hash‖seq` is struck — it left `v`, `issued_at`, `payload_type` unsigned and used non-canonical concatenation. There is exactly one signing-input rule in the system after this ADR.

### Authenticity note (do not silently rely on box authentication we no longer have)

A sealed box provides **confidentiality** and **integrity-of-ciphertext** (the recipient knows the ciphertext was not modified in transit, because Poly1305 verifies under the recipient-derived key) — but it provides **NO sender authentication**. Anyone holding `parent_x25519_pub` (which is not secret) can fabricate a well-formed sealed box that the parent will decrypt. Authenticated `crypto_box` *would* have bound the sender's identity into the key; by mandating sealed box we deliberately give that up at the encryption layer. Authenticity is therefore supplied **entirely by the entry-level Ed25519 signature** over the canonical envelope ([`PROTOCOL.md:342`](../PROTOCOL.md) §6.3: *"Sealing is for confidentiality ...; signing is for authenticity ... Both are required."*). The outer `sig` (ruling 3) is what proves the event came from the pinned child identity and was not forged or replayed. **This separation is load-bearing and must not be weakened:** removing or narrowing the entry signature would leave events unauthenticated, since the sealed box authenticates nothing about the sender. The design does not — and must not be read to — rely on confidentiality-layer authentication it no longer has.

**Caveat (rooted child).** The entry Ed25519 signature proves an event came from the pinned child *key* — which a rooted child controls. Against a fully-compromised (rooted) child, the signature therefore does NOT prevent forged events or alternate chains; the child is inside the trust boundary for its own key. Authenticity against the root adversary is not a property of any on-device signature — it rests on the append-only hash chain plus parent-side monotonicity/length detection (see [ADR-017](017-replay-rollback-resistance.md) and red-team finding K2 in [`../research/07-redteam-design-review.md`](../research/07-redteam-design-review.md)) and is defense-in-depth, not prevention. What the entry signature *does* buy: integrity against transit/at-rest tampering by a non-root party and binding to the pinned child identity.

## Consequences

**Good:**
- The "child cannot read own log" invariant ([`CLAUDE.md`](../../CLAUDE.md)) is preserved by construction: no decryption-capable secret is ever retained on the child.
- Events decrypt on the parent. The BLAKE2b nonce fix removes the silent fail-open (`crypto_box_seal_open` succeeds).
- One signing rule eliminates the `v` / `issued_at` / `payload_type` malleability and the `SealedEvent`→`Event` downgrade vector; closes the JCS-vs-concatenation ambiguity.
- No new primitives, no ratchet state machine, smaller attack surface, easier conformance testing against libsodium.
- The existing test vectors stand: `sealed_envelope.json` ([`CRYPTO.md:488-492`](../CRYPTO.md)) verifies open-roundtrip (which would have caught the BLAKE3 nonce), and the byte-for-byte `crypto_box_seal` conformance item ([`PROTOCOL.md:467`](../PROTOCOL.md)) is now consistent with the prose.

**Bad / accepted trade-offs:**
- **No sender authentication at the encryption layer** — see the authenticity note. Mitigated and made explicit: authenticity rests on the entry Ed25519 signature, which is mandatory and non-removable.
- No forward secrecy against parent-key compromise: holding the parent X25519 private key decrypts all captured past event logs (per-message ephemeral *sender* keys only stop a compromised *child* from decrypting, not a holder of the parent key). Accepted: events are short, metadata-only ([`CLAUDE.md`](../../CLAUDE.md) "No content in event log"), and a retained ratchet secret on the child is precisely the thing that would re-break the invariant. Parent-key hygiene (recovery-phrase-derived, never on a server) is the mitigation. If a future AI-summary message channel ever needs a ratchet, it is a **separate** channel requiring its own ADR (and would not touch the event log).
- STORE_AND_FORWARD readers lose the (incorrect) crypto detail; they are redirected to the normative docs.

## Doc changes required

These are the exact edits a follow-up PR must make. This ADR creates no code and edits no doc other than itself.

1. **[`STORE_AND_FORWARD.md`](../STORE_AND_FORWARD.md) — add a normative-deference banner** at the top of the "End-to-end encryption" section (before line 70): *"NON-NORMATIVE for cryptography. All event-log crypto (encryption primitive, nonce derivation, signing input) is governed by `CRYPTO.md` §4–5 and `PROTOCOL.md` §1.1 and §6. On any conflict, those docs win. See ADR-015."*

2. **[`STORE_AND_FORWARD.md:72`](../STORE_AND_FORWARD.md) — strike authenticated box.** Replace *"encrypted to the recipient via `box(payload, recipient_pubkey, sender_privkey)` (NaCl/libsodium box semantics)"* with: *"encrypted to the recipient via anonymous `crypto_box_seal` (sealed box). The sender keypair is the per-message ephemeral key generated and zeroized by libsodium; the child's long-term X25519 key is never used to encrypt events, so the child cannot decrypt its own log. See `CRYPTO.md` §4."*

3. **[`STORE_AND_FORWARD.md:76`](../STORE_AND_FORWARD.md) — strike the double-ratchet for the event channel.** Replace the "Forward secrecy" line with a note that the event channel uses sealed box (per-message ephemeral keys) and that any ratcheted message channel is out of scope for the event log and would require a separate ADR.

4. **[`STORE_AND_FORWARD.md:28`](../STORE_AND_FORWARD.md) — unify the signing input.** Replace `sig: Ed25519(payload || prev_hash || seq, owner_privkey)` with: *"`sig: Ed25519(JCS_canonical(entry without sig field), owner_privkey)` — covers `v, seq, prev_hash, issued_at, payload_type, payload`. See `PROTOCOL.md` §1.1 / §3."* (Update the `Entry` pseudo-struct comment at lines 22–29 to match.)

5. **[`PROTOCOL.md:319`](../PROTOCOL.md) (§6.2) — BLAKE3 → BLAKE2b.** Replace `nonce := BLAKE3-256(ephemeral_pub || parent_x25519_pub)[0..24]` with `nonce := BLAKE2b-192(ephemeral_pub || parent_x25519_pub)   // 24 bytes, libsodium crypto_box_seal construction`. Keep the line at [`PROTOCOL.md:326`](../PROTOCOL.md) (*"libsodium sealed-box construction verbatim"*) — it becomes true after this edit. Optionally add a one-line caution that implementations MUST call `crypto_box_seal` rather than hand-rolling the nonce. **Do not** change the BLAKE3-256 `prev_hash` chain in §1.2.

6. **No change needed** to the signing rule in PROTOCOL §1.1 (line 41), §3 (line 163), §6.3 (line 342), or CRYPTO §5 (lines 237, 247) — they are already the single correct rule and become the canonical reference. CRYPTO.md §4 (line 198) BLAKE2b is already correct.

## Cross-refs

- [`docs/CRYPTO.md`](../CRYPTO.md) §4 (sealed box, lines 165–220), §5 (signing, lines 224–262) — NORMATIVE
- [`docs/PROTOCOL.md`](../PROTOCOL.md) §1.1 (entry/signing, lines 18–41), §1.2 (BLAKE3 chain, lines 43–45), §3 (JCS, lines 161–192), §6 (event encryption, lines 299–346), §10.1 (conformance, lines 464–469) — NORMATIVE
- [`docs/STORE_AND_FORWARD.md`](../STORE_AND_FORWARD.md) "End-to-end encryption" (lines 68–76), "Logs" (lines 18–34) — non-normative for crypto after this ADR
- [`CLAUDE.md`](../../CLAUDE.md) — architecture invariants ("Event log encrypted to parent pubkey via libsodium sealed-box"; "No content in event log")
- [`docs/DEFENSES.md`](../DEFENSES.md) — Pattern B (child writes ciphertext it cannot decrypt), Defense #13 (constant-size traffic)
- [`docs/ATTACKS.md`](../ATTACKS.md) — H5 (forge child events), H1/C8 (replay)
- [`docs/adr/006-privacy-no-server.md`](006-privacy-no-server.md) — house ADR format reference
