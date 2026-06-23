# ADR-036: Parent pairing endpoint (b) — pre-auth gate, rate-limit, structural per-attempt token, fail-closed handoff
Status: Accepted
Date: 2026-06-22
Implements: **ADR-025 D5(b)** (the §7.2 receive half of the parent pairing flow); resolves the ADR-025 "Blocking before implementation" items that bind the endpoint
Builds on: **ADR-035** (the slice-(a) pairing session — CSPRNG nonce + §7.1 QR); issue #95
Relates: docs/PROTOCOL.md §7.2 (child response), §9.1 (test vectors); ADR-025 D6 (byte-level pubkey validation); ADR-030/031 (LAN transport — app-layer auth, mDNS, SPKI pin); docs/ATTACKS.md H3 (pubkey substitution); docs/DEFENSES.md #4/#14

## Context

ADR-025 D5 re-scoped the parent half of pairing into five slices (a)–(e). Slice (a) shipped (ADR-035): the parent mints a single-use CSPRNG `provisioning_nonce`, assembles the §7.1 QR, and owns the session lifecycle (single-active, TTL, burn). This ADR covers **slice (b)**: the **endpoint that receives the child's §7.2 POST over the LAN**.

The §7.2 body (PROTOCOL §7.2, as amended by ADR-032) is:

```json
{ "v":1, "child_ed25519_pub":"b64url(32)", "child_x25519_pub":"b64url(32)",
  "child_attestation_cert_chain":["base64(DER)", ...], "child_binding_sig":"hex(ECDSA-P-256)" }
```

The endpoint is **pre-pin**: it runs before any child key is pinned, and it is **mDNS-discovered** (#21), so it is reachable by anything on the LAN, including a hostile responder. Slice (b) must therefore be a hardened, fail-closed front door that does **only** what it can do safely without the trust anchor: bound the attack surface, validate the *shape and bytes* of the request, and hand a clean, session-bound object to slice (c) for the actual §7.3 attestation + §7.4 SAS crypto. **Slice (b) verifies no attestation, derives no SAS, and pins nothing.**

ADR-025's "Blocking before implementation" section flagged four items that bind this slice. This ADR resolves the ones that live at the endpoint layer; the rest are explicitly carried to (c)/(d)/(e).

## Decision

**D1 — Slice (b) is shape + gate + handoff, never trust.** The endpoint receives the POST, applies the pre-auth gate (D2), validates the request shape and the D6 byte-level pubkey encoding (D3), and hands a validated, session-bound object to a **verifier seam** (D4). It never decodes the cert chain into a trust decision, never derives the SAS, and never pins. All of that is slice (c)/(d)/(e).

**D2 — Pre-auth gate (resolves ADR-025 "pairing-endpoint pre-auth" + "per-attempt session token").** Because §7.1 is frozen canon (slice (a) shipped the QR with `transport_hints = {mdns, iroh_ticket?}` and no token field), we do **not** add a bearer-token wire field — that would be a `proto`/§7.1 change. Instead the per-attempt token is **structural**, and the cryptographic per-attempt binding is the nonce, enforced in (c):

1. **Single active session.** `PairingSessionManager` holds at most one live session. The endpoint accepts a POST **only while a session is live** (`active() != null`); none/expired ⇒ refuse. An expired session self-burns on read (ADR-035 D3).
2. **Per-session attempt cap.** At most `MAX_ATTEMPTS_PER_SESSION` (default **5**) POSTs are processed against a given session; on exhaustion the endpoint **burns the session** (forcing a fresh QR) and refuses. This bounds a flood/race against one pairing attempt without letting a single malformed POST kill the attempt.
3. **Per-source rate limit.** A fixed-window limiter (default **10 requests / 10 s** per source) throttles a hostile LAN peer before any parsing work.
4. **Body size cap.** Requests larger than `MAX_BODY_BYTES` (default **16 KiB** — a ~3-cert DER chain is >2 KiB but well under this) are refused before full read/parse.

The literal "per-attempt session token" of the ADR-025 blocking note is thus satisfied **structurally** (one live session + attempt cap + rate limit), with the actual per-attempt cryptographic token being the `provisioning_nonce` — bound into the attestation challenge and `child_binding_sig` and verified in slice (c), **not** by slice (b). Maintainer-approved (issue #95 attended decision, no wire change).

**D3 — Byte-level validation (ADR-025 D6), fail-closed, no oracle.** Each pubkey field is base64url-**decoded** and asserted to be **exactly 32 bytes** (not a length-only string check — the gap PR #64 had). The slice-(a) `Base64Url` encoder gains a strict pure-Kotlin **`decode`** (rejects padding, non-alphabet characters, and invalid lengths; returns `null` rather than throwing) plus `decode32`. The cert chain and `child_binding_sig` get **light shape checks only** at this layer (chain non-empty and within a sane count; sig a non-empty hex string) — full DER/ECDSA parsing is slice (c). `v` must equal `1`. Any failure refuses the POST. **Refusal responses are coarse** (a generic `400`, with `429` for rate/attempt limits) so the endpoint is not a probing oracle for *which* check failed.

**D4 — Verifier seam = the handoff to slice (c), fail-closed by default.** A shape-valid, session-bound POST is handed to an `AttestationVerifier` seam as a `ValidatedPairingPost` (the live `PairingSession`, the parsed body, and the two validated 32-byte pubkeys). Slice (b) returns the verifier's verdict verbatim. The shipped default is **`RefuseAllAttestationVerifier`** — until slice (c) (#96) lands, every well-formed pair **refuses** (fail-closed; nothing pins on a stub). Slice (b) does **not** burn the session on a verifier refusal — burning the nonce on a *failed attestation or SAS mismatch* is slice (c)/(d)'s blocking item (ADR-025), so that lifecycle decision is left to them; slice (b) only burns on the attempt-cap (its own pre-auth concern).

**D5 — Thread marshaling (resolves ADR-035's deferred item).** ADR-035 confined the session manager to a single thread and deferred cross-thread access to "the (b) slice." Slice (b) accesses the manager only through a `SessionAccess` seam; the Android adapter (`LockedSessionAccess`) serializes all session touches under one shared monitor that the pairing coordinator also holds, and the Ktor handler runs each request under that same (reentrant) lock. Host tests use a direct synchronous `SessionAccess` and drive the endpoint single-threaded.

**D6 — Substrate: pure handler + thin Ktor-CIO adapter.** All security logic lives in a pure `commonMain` `PairingEndpoint.handle()` (host-tested, deterministic, seam-injected clock) — mirroring slice (a). A thin `androidMain` `PairingServer` (Ktor **CIO**, `POST /pair`, bound `0.0.0.0` only while a session is live, mirroring the child `ApiServer`) reads the body, calls `handle()`, and maps the result to an HTTP status. The adapter carries no validation logic of its own.

## Consequences

Good:
- The pre-pin LAN front door is hardened and fail-closed before any trust exists; H3/MITM defenses are preserved because slice (b) pins nothing and defers all key trust to (c)/(d)'s attestation + four-key SAS.
- D6 byte-level validation is enforced exactly where untrusted child input first enters the parent.
- No wire/`proto` change: §7.1 and the shipped slice-(a) QR are untouched.
- All security logic is host-testable; the device-only surface is a thin transport adapter.

Bad / accepted residuals (carried forward, disclosed):
- **mDNS *advertising* of the parent pairing endpoint is not wired here.** The server binds the LAN; advertising it for child discovery rides on the parent-side mDNS work (#21 / transport wiring). Until then the child reaches it by address. Tracked, not regressed.
- **Burn-on-attestation-failure is slice (c)/(d)'s.** Slice (b) deliberately does not burn the nonce on a verifier refusal; (c)/(d) own that (their ADR-025 blocking items). The attempt cap still bounds abuse in the interim.
- **Full-transcript replay binding** (ADR-025 blocking item) is a (c)/(d) concern (it needs the attestation transcript); slice (b) provides only the single-use-session + attempt-cap substrate.

## Test plan (this slice)

Deterministic `commonTest`, seam-injected clock + fakes (matching `PairingSessionManagerTest`):
- **Handoff:** a valid POST against a live session is parsed and handed to the verifier (fake verifier observes the validated `ValidatedPairingPost`; decoded pubkeys are exactly 32 bytes).
- **Fail-closed default:** with `RefuseAllAttestationVerifier`, a valid POST refuses (nothing pins).
- **No / expired session ⇒ refuse** (expired self-burns).
- **Replay:** a second POST after the session is burned ⇒ refuse.
- **Attempt cap:** the (N+1)th POST burns the session and refuses; subsequent POSTs see no session.
- **Rate limit:** bursts beyond the window are throttled, then recover after the window.
- **Body size cap:** an over-cap body is refused before parse.
- **D6 byte-level pubkey validation (vectors `pair-01` / `pair-03`):** `pair-01` (exactly 32 decoded bytes) accepted; `pair-03` (non-base64url, wrong length, over-long 32.25-byte blob, padded) rejected. Vectors under `docs/test-vectors/pairing/`.
- **Malformed body / bad `v` / bad cert-chain shape / bad sig encoding ⇒ refuse.**

## Cross-refs
- [docs/PROTOCOL.md](../PROTOCOL.md) §7.2, §9.1
- [ADR-025](025-pairing-handshake-direction-attestation-sas.md) D5(b)/D6 + Blocking section
- [ADR-035](035-parent-pairing-session-nonce-qr.md) (slice (a); deferred marshaling resolved here, D5)
- [ADR-030](030-lan-server-auth-signed-commands.md) / [ADR-031](031-lan-transport-confidentiality-identity-bound-spki.md) (LAN transport)
- docs/ATTACKS.md H3; docs/DEFENSES.md #4/#14; issue #95
