# ADR-035: Parent pairing session — CSPRNG nonce lifecycle + §7.1 QR payload (implements ADR-025 D5a)

Status: Accepted
Date: 2026-06-22
Implements: **[ADR-025](025-pairing-handshake-direction-attestation-sas.md) D5(a)** — the first of the five re-scoped parent-side pairing slices. Resolves the **nonce-lifetime + single-use consumption** item from ADR-025 "Blocking before implementation".
Relates: docs/PROTOCOL.md §7.1 (QR payload); [ADR-033](033-parent-root-key-recovery-phrase.md) (the parent root keys carried in the QR); [ADR-032](032-child-identity-hardware-binding-strongbox-p256.md) (the child side that consumes the nonce as its attestation challenge); docs/ATTACKS.md H3; docs/DEFENSES.md #14 / Pattern E. Closes part of issue **#94** (re-scope of closed #23).

## Context

[ADR-025](025-pairing-handshake-direction-attestation-sas.md) ratified PROTOCOL §7 as protected canon and re-scoped the parent half of pairing into five slices (D5 a–e). It also recorded four conformance items under "Blocking before implementation" that each implementation slice must resolve fail-closed. This ADR governs **slice (a)**: the parent generates the one-time pairing session and assembles the **§7.1 QR payload it displays** (the parent never scans — that inversion is forbidden, ADR-025 D4).

§7.1 fixes the QR contents:

```json
{ "v":1, "parent_ed25519_pub":"b64url(32)", "parent_x25519_pub":"b64url(32)",
  "provisioning_nonce":"b64url(32)", "transport_hints":{ "mdns":"_openwarden._tcp.local" } }
```

What §7.1 leaves to the implementation — and ADR-025 explicitly deferred — is the **nonce lifecycle**: its TTL, whether it survives a parent restart, exactly when it is consumed, and that a failed attempt burns it. Those are security decisions (a stale or reusable nonce weakens the attestation-challenge freshness slice (c) relies on), so they need this record before code lands. The `provisioning_nonce` is consumed by the child as its StrongBox `setAttestationChallenge` input (§7.2), and slice (c) refuses the pair unless the attested leaf's challenge equals the nonce the parent issued — so the nonce's freshness and single-use property are load-bearing for the H3 anti-swap defense, even though slice (a) only *mints* it.

## Options

- **A. In-memory, single active session, short TTL, fail-closed on every loss (chosen).** The session (nonce + parent-key snapshot + timing) lives only in process memory; one pending attempt at a time; a new start, an expiry, a cancel, or a consume all burn it. Any loss (restart, expiry) resolves to **unpaired**, never half-pinned. Simplest surface; matches the §7 "failure here is unrecoverable except by re-pair" stance.
- **B. Persist the pending nonce across restart (rejected).** Lets a pairing survive a parent app restart. But a persisted pre-pin secret is attack surface for no real benefit — pairing is a deliberate side-by-side bench/OOBE step; if the parent restarts mid-pair, re-displaying a fresh QR costs seconds. Persistence also complicates "single-use burn" (now must survive crashes) and risks a stale nonce being reused. Rejected: more surface, weaker freshness, negligible UX gain.
- **C. Multiple concurrent sessions (rejected).** Allow several pending nonces so the parent can pair more than one child at once. Creates nonce ambiguity at the (b) endpoint (which session does an inbound POST match?) and multiplies the live-secret window. v0.3 pairs one child per attempt; rejected as premature.
- **Nonce source:** a **`PairingNonceSource` seam** (Android `SecureRandom` actual; deterministic fake in tests) rather than reusing the existing 16-byte hex `NonceGenerator` (PROTOCOL §2 anti-replay nonce) — §7.1 needs **32 bytes, base64url**, a different shape and purpose, so conflating them would be wrong.

## Decision

Adopt **Option A**. Nine parts.

**D1 — Scope is slice (a) only.** This ADR + PR deliver: the §7.1 payload assembly, the CSPRNG nonce, and the session-lifecycle API (`isExpired`, `consume`, `cancel`, single-active). It does **not** implement the endpoint (b), attestation verify (c), SAS (d), or pin (e); those are issues #95–#98. The session exposes the lifecycle hooks those slices drive.

**D2 — Nonce = 32-byte CSPRNG, base64url, single-use.** A `PairingNonceSource.freshNonce(): ByteArray` seam yields exactly 32 CSPRNG bytes; the Android actual wraps `java.security.SecureRandom`. The nonce is base64url-encoded (RFC 4648 §5, **no padding** → 43 chars) into the QR. It is single-use per pair attempt (D6). A non-32-byte value from the seam is a programming error and is rejected (`require`) — fail-closed, never emit a short nonce.

**D3 — TTL is bounded and clock-injected; default 300 s.** The session records `createdAtMs` and `ttlMs` (default **300_000 ms = 5 min**, constructor-configurable). Expiry is evaluated against an injected `nowMs: () -> Long` clock (mirrors `PolicySender`'s `clockMs` seam) so it is deterministically testable. 5 minutes covers QR scan + StrongBox `K_bind` keygen (slow on first run) + the child POST, while keeping the live-secret window short. The TTL bounds resource/replay exposure on the pre-pin endpoint; it is **not** the trust gate — the pin is gated by attestation (c) **and** the four-key SAS (d). Expiry burns the session (D6).

**D4 — In-memory only; not persisted across a parent restart (fail-closed).** The session and nonce never touch disk. A parent restart mid-pair discards the session; the child's later POST then finds no live session and is refused (slice b), forcing a clean re-pair. This is the fail-closed answer to ADR-025's "does it persist across a parent restart?" — **no, by design**: a lost session resolves to *unpaired*, never a stuck or half-trusted state.

**D5 — One active pending session; start burns the prior.** `start()` overwrites any existing pending session, burning its nonce first. Only one pending pair attempt exists at a time, so the (b) endpoint never has to disambiguate which nonce an inbound POST belongs to.

**D6 — Burn points (defined here; failure-path callers land with their slices).** The session/nonce is cleared on: (i) `consume()` — the success handoff completed by slice (e); (ii) TTL expiry; (iii) a new `start()`; (iv) `cancel()`. `consume()` is idempotent: after it, `active()` returns `null`. The attestation-failure (c) and SAS-mismatch (d) burns are `cancel()` calls those slices make; slice (a) provides the API and the expiry/new-start/cancel/consume burns now. After any burn, no nonce is retrievable.

**D7 — Fail-closed on missing parent keys.** `start()` reads `RootKeyProvider.rootPublicKey()` and `encryptionPublicKey()`. If either is `null` (the parent has no confirmed recovery phrase yet — ADR-033), `start()` returns `NotProvisioned` and creates **no session and no QR**. There is no pairing before the parent root key exists. Both pubkeys are length-checked (== 32 bytes) before assembly; a malformed key refuses, fail-closed.

**D8 — Wire is §7.1 verbatim; there is NO `tls_spki`.** The payload carries exactly `{v:1, parent_ed25519_pub, parent_x25519_pub, provisioning_nonce, transport_hints}`. The invented `tls_spki` field (PR #64) stays rejected (ADR-025 D3) — the TLS sync channel is pinned against the pinned pubkey post-pairing (ADR-031), not via an SPKI hash in the QR. `transport_hints` for v0.3 LAN is `{ "mdns": "_openwarden._tcp.local" }`; `iroh_ticket` is omitted (v2), an optional forward-compatible field.

**D9 — QR bitmap rendering is the presentation tail, deferred to the parent pairing UI.** This slice produces the exact payload string the QR encodes — the security-relevant surface (nonce, keys, schema). Turning that string into an on-screen QR bitmap is a non-security presentation transform that lands with the parent pairing screen, keeping this agent-blocked PR tightly scoped to the reviewed crypto/protocol surface. The payload string is the contract; the image is a faithful encoding of it.

## Consequences

Good:
- The nonce-lifecycle blocking item (ADR-025) is resolved fail-closed and recorded; slices (b)–(e) inherit a defined session API.
- No persisted pre-pin secret; the live-secret window is one short-TTL in-memory nonce, burned on every terminal path.
- Fail-closed on both missing parent keys (D7) and any session loss (D4) — never a half-pinned state.
- `tls_spki` stays dead; the QR is §7.1 verbatim.
- The logic is pure-Kotlin in `commonMain`, host-testable in `commonTest` with seam-injected nonce + clock — deterministic, no device.

Bad / accepted limits:
- A parent restart mid-pair forces a re-pair (D4). Accepted: pairing is a one-time bench/OOBE step.
- One pending pair at a time (D5). Accepted for v0.3 (one child per attempt).
- The 5-min TTL is a judgement call; it is configurable and can be tuned when slice (b)/(c) measure real StrongBox keygen + POST latency on device.
- This slice does not render a scannable image (D9); the parent UI wiring completes that.

## Forward hazards (recorded for slices b–e)

The dual adversarial review of this slice surfaced three forward-looking constraints. None is a defect in slice (a) — they are preconditions the downstream slices inherit and must not silently drop:

- **The QR JSON is deliberately non-canonical and unsigned.** `toJson()` emits kotlinx-serialization declaration-order JSON, not RFC 8785 JCS (PROTOCOL §3 / ADR-019). Correct here — the §7.1 QR carries no signature; trust comes from attestation (c) + the four-key SAS (d), and the child parses by field name. **Constraint:** any future transcript binding (the ADR-025 "full-transcript replay binding" item) MUST hash the raw key/nonce **bytes** or a JCS re-encoding — never this serializer output — or it reintroduces the signer/verifier byte-drift ADR-019 forbids.
- **`consume()`/`active()` are not internally synchronized** (no `synchronized` in commonMain; slice (a) is single-threaded UI). The check-then-null in `consume()` is non-atomic. **Constraint (issue #95, the b endpoint):** the endpoint MUST confine or synchronize `consume()`/`active()` onto one context, with a concurrency test — else two concurrent child POSTs could be handed the same nonce, weakening the single-use property slice (c) depends on.
- **`ttlMs` is caller-supplied and unbounded** (the 300 s default is correct). **Constraint:** when the parent pairing UI wires the real value, clamp it to a sane maximum so the live pre-pin window cannot be widened past D3's intent.

## Test plan

Deterministic, seam-injected (fake `PairingNonceSource` + `nowMs` lambda), host-side in `commonTest`:

- **Payload shape:** `start()` on a provisioned `RootKeyProvider` → §7.1 JSON with `v==1`, `parent_ed25519_pub` / `parent_x25519_pub` / `provisioning_nonce` each a 43-char base64url string that decodes to exactly 32 bytes, and `transport_hints.mdns == "_openwarden._tcp.local"`; no `tls_spki` key present.
- **base64url:** RFC 4648 §5 vectors; url-safe alphabet (`-`/`_`, never `+`/`/`); no `=` padding; 32-byte input → 43 chars.
- **Fail-closed key access (D7):** not-provisioned provider → `NotProvisioned`, no session.
- **Nonce freshness (D2):** two `start()`s with a counter-based nonce source → distinct nonces.
- **Single active + burn (D5/D6):** a second `start()` burns the first session's nonce; `consume()` returns the live session then `active()` → `null`; `cancel()` → `active()` null.
- **TTL expiry (D3):** with the injected clock advanced past `ttlMs`, `active()` → `null` (burned) and `consume()` → `null`.

## Cross-refs
- docs/PROTOCOL.md §7.1 (QR payload), §7.2 (the child consumes the nonce as its attestation challenge)
- [ADR-025](025-pairing-handshake-direction-attestation-sas.md) D1/D4/D5(a)/D3 + "Blocking before implementation" (nonce lifecycle)
- [ADR-033](033-parent-root-key-recovery-phrase.md) (parent root keys), [ADR-032](032-child-identity-hardware-binding-strongbox-p256.md) (child attestation challenge), [ADR-031](031-lan-transport-confidentiality-identity-bound-spki.md) (TLS pin against pubkey, not SPKI-in-QR)
- docs/ATTACKS.md H3; docs/DEFENSES.md #14 / Pattern E
- issues #94 (this slice), #95–#98 (b–e); closed #23; PR #64 (rejected inversion)
