# ADR-031: LAN transport confidentiality — mDNS discovery + TLS SPKI pinned to the child identity key (closes issue #21 / red-team TR1)

Status: Accepted
Date: 2026-06-21
Relates: **ADR-030** (the app-layer Ed25519 command/auth surface this builds on — D1 there already declared auth transport-independent), **ADR-025** (pairing pins the child Ed25519 identity key; D3 there **kills** the `tls_spki` QR field — this ADR's binding obeys that), **ADR-015/019** (the one signing rule — JCS canonical bytes minus `sig`), **ADR-028** (Bluetooth transport — inherits the same confidentiality-orthogonal-to-auth split); docs/PROTOCOL.md §4 (transport state machine: "HTTPS with self-signed cert pinned post-pairing", "cert pinning against pubkey"), §7 (pairing); docs/research/07 **TR1** (the finding this closes); docs/ATTACKS.md (LAN MITM); docs/DEFENSES.md

## Context

Red-team finding **TR1** (docs/research/07): *"TLS pin material has no defined provenance. The QR (PROTOCOL §7.1) ships identity pubkeys but **not** the REST TLS cert/SPKI → first-connect TOFU loses to LAN MITM (ARP/mDNS spoof of `_openwarden._tcp`) on first sync."* Suggested mitigation: *"Document TLS as confidentiality-only (every entry must pass app-layer verify regardless of transport); pin TLS SPKI to the identity key or carry it in the QR."*

PROTOCOL §4 already names the v1 transport — `_openwarden._tcp.local`, **"HTTPS with self-signed cert pinned post-pairing"**, state machine `PROBING → cert-pin ok → EXCHANGING_DIGEST` / `cert-pin fail → ERROR`, **"cert pinning against pubkey"** — but the *provenance* of that pin (how the parent knows a presented cert is the genuine child's, with no trust-on-first-use window) was never specified. That gap is TR1.

**The design space is already half-closed by canon.** TR1 offers two arms — *(a)* pin TLS SPKI to the identity key, or *(b)* carry the SPKI in the QR. **ADR-025 D3 forecloses arm (b):** the invented `tls_spki` QR field of the rejected PR #64 is killed, because adding a pairing wire-format field is a `proto` change (agent-blocked) and because §7's TLS pin is defined as *"against the pinned pubkey"*, not a separate hash shipped in the QR. So this ADR takes **arm (a)**: bind the TLS SPKI to the already-pinned **child Ed25519 identity key**, with the binding produced and verified *post-pairing at runtime* — no new pairing artifact.

**Why this is a design decision, not a mechanical fix.** "Pin the cert against the pubkey" is one sentence in PROTOCOL §4; turning it into a concrete, fail-closed, testable provenance scheme requires choosing *how* a 256-bit TLS SPKI is bound to a 256-bit Ed25519 identity key. Two real candidates were weighed (see Options); the choice has crypto and Android-platform consequences, so it is recorded here and human-approved.

**State of the code this lands on.** The child runs an embedded Ktor **CIO** server (`ApiServer.kt`, plain HTTP, port 7180). There is **no** TLS, **no** mDNS, and **no child identity keypair** yet — `DeviceIdentity` only mints a random 16-byte audience id; the StrongBox-backed child Ed25519 identity key is generated at pairing (issue #22, not yet built). The child holds only the **pinned parent** key. App-layer authentication (`CommandAdmission` / `BundleVerifier` / `HeartbeatVerifier`, ADR-030) is already a pure, transport-independent decision.

## Options

- **A. Identity-key signature over the TLS SPKI (a "signed assertion"), chosen.** The child keeps an ordinary self-signed TLS leaf cert; its identity key signs a tiny `SpkiAssertion{v, spki_sha256, sig}` vouching for that cert's SPKI. The parent, holding the pinned child identity pubkey, accepts the channel iff the presented leaf cert's SPKI matches the assertion **and** the assertion's Ed25519 signature verifies against the pinned key. Works with any TLS certificate/stack; reuses the one signing rule (ADR-015); no key reused across roles in an unusual primitive; the assertion is served at runtime, so **no pairing/QR wire field** (honors ADR-025 D3).
- **B. Use the Ed25519 identity key directly as the TLS leaf key (RFC 8410).** Then `SPKI == identity pubkey` and "pin against pubkey" is literal, with nothing extra to verify. **Rejected:** Android's TLS stack (Conscrypt) + Ktor/Netty support for Ed25519 *certificate* key exchange is incomplete and version-fragile; and it reuses the long-term identity key as the TLS key (cross-role key reuse, discouraged). Its sole advantage — no separate assertion — does not pay off while the live TLS socket is deferred (see D5), so it would be a fragile coupling with no present benefit. May be revisited in a future ADR if/when Ed25519 TLS support is dependable.
- **C. Carry the SPKI hash in the pairing QR (TR1 arm b / PR #64's `tls_spki`).** **Rejected by ADR-025 D3** — a `proto`/pairing-wire change, agent-blocked, and explicitly killed there.

## Decision

Adopt **Option A**. Six parts.

**D1 — TLS is confidentiality-only; the authentication floor is the app-layer Ed25519 signature, and it is transport-independent.** Every state-changing message is admitted **only** by its app-layer signed-object check (`CommandAdmission` for lock/unlock, `BundleVerifier` for `/policy`, `HeartbeatVerifier` for `/heartbeat`) against the **pinned parent key** — exactly as on plain HTTP today (ADR-030 D1). TLS adds confidentiality and integrity *of the channel*; it never becomes an authentication shortcut. **A tampered payload delivered over a fully trusted TLS socket still fails app-layer verify**, and an authentic payload over plain HTTP still passes. Confidentiality (this ADR) and authentication (ADR-030) are orthogonal; neither blocks the other, and neither weakens if the other is absent (this is the TR1 "every entry must pass app-layer verify regardless of transport" requirement, made normative and tested).

**D2 — TLS SPKI provenance = a child-identity-signed `SpkiAssertion`; no trust-on-first-use.**

```
SpkiAssertion { v: 1, spki_sha256, sig }
```

- `spki_sha256` = **base64url(SHA-256(DER(SubjectPublicKeyInfo) of the TLS leaf cert))** — RFC 7469 SPKI-pin semantics (the pin survives cert renewal that keeps the same key).
- `sig` = hex Ed25519 by the **child identity key** over the **RFC 8785 JCS canonical bytes of the object minus `sig`** — the identical rule as `SignedBundle`/`SignedCommand`/`SignedHeartbeat` (ADR-015/019). The signer canonicalizes `{v, spki_sha256}`; the verifier canonicalizes the object it received minus `sig`; both emit byte-identical input.
- **Verification (the logic the *parent* runs; shipped child-side as the shared primitive and the acceptance test):** accept the channel iff **all** hold, else **reject** (refuse the channel; never TOFU-accept):
  1. a pinned child identity pubkey exists (else reject — nothing to verify against; pre-pairing has no trust anchor);
  2. `v == 1`;
  3. `spki_sha256` is non-empty **and equals** the SHA-256 of the SPKI the peer **actually presented** on the wire — this binds the assertion to *this* cert, so an attacker replaying the genuine child's assertion while presenting its **own** cert fails here;
  4. the Ed25519 signature verifies over the canonical body against the **pinned child identity key** — an attacker who lacks the child identity private key cannot forge an assertion for its own SPKI.
- **Domain separation** is by JCS object shape, exactly as ADR-030 separates `SignedCommand`/`SignedHeartbeat`/`SignedBundle` under one key: `SpkiAssertion`'s key set `{v, spki_sha256}` is disjoint from every other signed wire object, so a signature over one canonicalizes to different bytes than any other and cannot be cross-replayed. (The assertion is signed by the **child** key; commands/bundles/heartbeats by the **parent** key — a second, independent separation.)

**D3 — mDNS `_openwarden._tcp` is *untrusted discovery only*; all trust comes from D1+D2.** The child advertises its LAN server via Android `NsdManager`: service type `_openwarden._tcp`, the listening port, and a TXT record carrying the child audience id (`DeviceIdentity` id) for disambiguation when several children share a LAN. **mDNS/TXT is unauthenticated and spoofable by design** (the ARP/mDNS-spoof vector TR1 names) — it is treated as a *hint*, never a trust signal. A spoofed `_openwarden._tcp` responder is defeated not by mDNS but by D2 (it cannot present a cert with a valid identity-bound assertion) and D1 (it cannot forge a parent-signed payload even if a client connects to it). Nothing in TXT is ever trusted; no secret is ever advertised.

**D4 — Fail-closed verifier; reject refuses the channel, never downgrades.** Any D2 check failing — missing pinned identity key, `v != 1`, empty/mismatched `spki_sha256`, empty/bad `sig`, malformed cert/SPKI/base64 — yields **reject**. On reject the parent MUST NOT fall back to an unverified/plaintext channel and MUST NOT TOFU-pin a presented cert; it refuses and surfaces a pairing/MITM error (per PROTOCOL §4 `cert-pin fail → ERROR`). There is no "first connection is trusted" path: a child with no pinned identity key (pre-pairing) yields reject, not accept.

**D5 — Scope is the child-side provenance primitive + discovery; the live TLS socket, the real identity key, and the parent half are disclosed-deferred siblings.** This ADR / issue #21 lands, child-side and seam-injected:
- the `SpkiAssertion` wire type, the `SpkiBinding` verifier (D2) and signer, and an injected **`IdentityKeyProvider`** seam;
- the `MdnsAdvertiser` (D3), wired into the server lifecycle, fail-safe (advertise failure never crashes the server nor weakens enforcement — advertising is orthogonal to policy);
- the **transport-independence guarantee** (D1) as an explicit regression test.

Explicitly **deferred**, each its own tracked issue (not silently dropped):
- the live TLS socket (the CIO server cannot easily terminate TLS; enabling HTTPS means a Netty/CIO-TLS engine change — its own change, on the enforcement surface);
- the **real StrongBox child identity key** (issue #22) — until it lands, `IdentityKeyProvider` returns `null`, so the signer **produces no assertion** (fail-closed: the child claims no confidential channel it cannot vouch for) and the verifier rejects (no pinned identity key);
- the **parent-side** discovery + cert-pin + reject (parent-kmp; the ADR-025 D5 re-scoped pairing issues).

The acceptance — *"a spoofed responder without the pinned SPKI/identity is rejected; tampered payloads fail app-layer verify even over a trusted socket"* — is met **deterministically** by the D2 verifier (the exact logic the parent runs) and the D1 regression test. The *live* E2E "parent app rejects a spoofed child on the LAN" is gated on the parent-side sibling and is not claimed here.

**D6 — Relationship to ADR-030's disclosed D4 residual.** ADR-030 D4 disclosed that on **plaintext** LAN a captured `unlock` is repeatably replayable within the freshness window. The confidential channel this ADR defines (once the deferred socket lands) closes the *capture* vector that residual depends on; until then ADR-030's 5-minute freshness window remains the bound, unchanged. This ADR lands the **provenance and auth-independence** that make the eventual confidential channel trustworthy **without** a TOFU window — i.e. it removes the *new* MITM exposure that naively adding TLS would have introduced (TR1), rather than itself flipping the socket.

## Consequences

**Good:**
- TR1 closed at the design level: TLS gains a defined, no-TOFU provenance (D2) bound to the already-pinned identity, and TLS is normatively confidentiality-only (D1) so adding it can never become an auth bypass.
- One signing rule end-to-end — the assertion reuses the same canonicalize-then-Ed25519 path as bundles/commands/heartbeats (ADR-015); no new primitive, no new pairing wire field (ADR-025 D3 honored).
- Discovery (mDNS) is honestly scoped as untrusted; the spoof defense lives in the cryptographic binding, not the discovery layer.
- Transport-independence is now a tested invariant, so future transports (TLS here, Bluetooth ADR-028) inherit the auth floor for free.

**Bad / accepted limits (disclosed):**
- **No confidentiality actually ships yet** — the live TLS socket is deferred (D5), so the ADR-030 D4 plaintext-replay residual persists until that sibling lands; this ADR makes the *future* socket safe, it does not yet encrypt the wire.
- **The binding cannot run for real until issue #22** supplies the StrongBox identity key; today the signer no-ops fail-closed and only the verifier + tests exercise the path (with an injected synthetic key). This is wiring-ahead, deliberately seam-injected.
- **The parent half is out of scope** (parent-kmp, ADR-025 D5); the end-to-end spoof rejection is proven by the shared verifier + unit tests, not yet by a running parent app.
- mDNS advertising adds a small always-on LAN broadcast of the child's presence + audience id (metadata only, never content/secret) — acceptable and consistent with KID_TRANSPARENCY (the child may see it is discoverable).

## Test plan (binds the implementation)

`SpkiBinding` unit tests (pure, deterministic):
- **Accept:** a genuine assertion (signed by the identity key over `{v:1, spki_sha256=H(presentedSpki)}`) verifies against the pinned identity key when the presented SPKI matches ⇒ accept.
- **Reject (each, fail-closed):** no pinned identity key; `v != 1`; empty `spki_sha256`; `spki_sha256` ≠ SHA-256 of the **presented** SPKI (the cert-substitution / spoofed-responder case — the acceptance); empty `sig`; signature by a **different** key (an attacker's identity key); malformed base64/cert bytes.
- **Cross-replay:** an assertion that is valid for cert X is rejected when presented with cert Y (check 3 binds it to the presented SPKI).
- **Signer round-trip:** `SpkiBindingSigner` with a present `IdentityKeyProvider` produces an assertion the verifier accepts; with a `null`-key provider it produces **no** assertion (fail-closed).

`MdnsServiceSpec` unit tests (pure): the built spec has type `_openwarden._tcp`, the given port (rejected outside 1..65535), and a TXT map carrying the child id; empty child id rejected.

Transport-independence regression (`CommandAdmission`, reusing ADR-030's path): a tampered `SignedCommand` is rejected regardless of transport, and a valid one is admitted independent of any socket — locking the D1 invariant.

## Cross-refs
- [ADR-030](030-lan-server-auth-signed-commands.md) D1/D4 — transport-independent app-layer auth; the plaintext-replay residual this confidentiality work eventually closes
- [ADR-025](025-pairing-handshake-direction-attestation-sas.md) D3 — `tls_spki` QR field killed; TLS pins against the pinned pubkey (this binding)
- [ADR-015](015-event-log-crypto-primitives.md) / [ADR-019](019-canonical-signing-invariant.md) — the one signing rule reused by `SpkiAssertion`
- docs/PROTOCOL.md §4 (transport state machine, "cert pinning against pubkey"), §7 (pairing pins the child identity key)
- docs/research/07 **TR1**; docs/ATTACKS.md (LAN MITM); docs/DEFENSES.md
- Issue #21 (this surface); issue #22 (child StrongBox identity key — D5 dependency); the parent-side pairing issues (ADR-025 D5)
