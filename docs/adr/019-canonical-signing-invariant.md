# ADR-019: Canonical signing — sign-and-transmit-exact-bytes, verify over received bytes

Status: Accepted
Date: 2026-06-16
Supersedes/relates: ADR-015 (one signing rule), ADR-017 (replay + JCS integer bound)

## Context

ADR-015 fixed **one signing rule**: every Ed25519 signature is over the RFC 8785 (JCS)
canonical form of the object with its `sig` field removed. `proto/SigningInput.kt` implements
it (`forBundle`/`forEntry` on the signer side, `forDocument` on the verifier side).

The PR #47 review surfaced a real interop hazard. `forBundle` derives canonical bytes from the
**typed model** with `Json { encodeDefaults = true }` (so empty `allowlist`/`blocklist`/`windows`
and the `v` default are emitted and signed). A verifier that **re-canonicalizes a parsed model**
can produce different bytes than the signer if the transmitted document differs from the signed
canonical form — a defaulted field omitted on the wire, an extra unknown field, reordering, or a
divergent number/Unicode encoding. The result is `SIG_FAIL` on a genuinely valid bundle. This is
the ADR-017 JC1 failure *mode* (signer/verifier canonical-byte divergence) arriving through a
schema-shape door rather than an integer door.

## Decision

**D1 — The signer transmits the exact canonical bytes it signed.** The canonical JCS encoding of
the unsigned object (no `sig` field) **is** the wire body. No re-serialization after signing.

**D2 — The verifier verifies over the bytes it received, verbatim.** Verification runs over the
exact received byte string (`SigningInput.forDocument` on the received JSON, or directly over the
raw request body). The verifier MUST NOT parse-then-re-canonicalize before verifying. Order is
strict and fail-closed: **verify first, parse second, apply third.** A verified-but-unparseable
body is rejected, never applied.

**D3 — `forBundle`/`forEntry` are signer-side conveniences.** Their output is valid only if
transmitted verbatim (D1). `forDocument` is the canonical verifier entry point. The typed model is
only ever a *post-verification* parse target.

**D4 — Integers bound + no nulls.** Every integer is asserted within `0..2^53-1` before
signing/verifying (ADR-017); numbers are integers-only. `null` values are forbidden in a signed
document (omit the key, per PROTOCOL.md §3.1) — the verifier path rejects them (enforcement in code
is a tracked follow-up; today's typed models never emit a null except the stripped `sig`).

**D5 — Key ordering is by UTF-16 code unit.** RFC 8785 §3.2.3 sorts object members by UTF-16 code
unit, which is what `Canonical.kt` (`String.sorted()`) does. PROTOCOL.md §3.1 prose is corrected
from "code-point" to "UTF-16 code unit" so the four required language ports stay byte-identical for
astral-plane keys.

## Consequences

Parent↔child interop is byte-stable: the child verifies the parent's libsodium signature over the
exact bytes the parent sent, with no second canonicalizer to drift. This was validated live on two
emulators (parent libsodium `crypto_sign_detached` → child net.i2p `EdDSAEngine` verify over
byte-identical canonical bytes → policy applied); see the `demo/*` branches and their
`019-pairing-and-canonical-transmit.md` (which records the **demo-grade transport** — plaintext
HTTP, no TLS/auth, `/policy` bypassing the `ingest()` replay/expiry pipeline — as explicitly
demo-only, NOT production).

**Out of scope here (follow-ups):** enforcing null-rejection in `SigningInput`; reconciling the
child `SignedBundle` wire schema with the proto `PolicyBundle` (`policy_seq`/`childDeviceId`/
`not_before`/`not_after` vs `issued_at`/`expires_at`/`nonce`); routing `/policy` through the full
`PolicyStore.ingest()` pipeline; and production transport (mDNS + pinned TLS + transport replay
window). None of these are required for the signing-input invariant this ADR fixes.

## References
ADR-015, ADR-017; `proto/SigningInput.kt`, `proto/Canonical.kt`; PROTOCOL.md §3.1; PR #47.
